package org.apache.maven.plugins.dependency.analyze;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.compress.utils.CharsetNames;
import org.apache.commons.compress.utils.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.StringModelSource;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Analyzes the dependencies of this project and determines which are: used and declared; used and undeclared; unused
 * and declared. It will then update the pom.xml to fix any dependency issues found.
 *
 * <p>By default, <a href="http://maven.apache.org/shared/maven-dependency-analyzer/">maven-dependency-analyzer</a> is
 * used to perform the analysis, with limitations due to the fact that it works at bytecode level, but any
 * analyzer can be plugged in through <code>analyzer</code> parameter.</p>
 *
 * @author <a href="mailto:jhaber@hubspot.com">Jonathan Haber</a>
 * @version $Id$
 * @since 3.0
 */
@Mojo( name = "fix", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true )
public class FixMojo
    extends AbstractAnalyzeMojo
{

  @Component
  ModelReader modelReader;

  @Override
  protected boolean isFailOnWarning()
  {
    return false;
  }

  @Override
  protected boolean isOutputXML()
  {
    return false;
  }

  @Override
  @SuppressWarnings( "unchecked" )
  protected void handle( Set<Artifact> usedUndeclared, Set<Artifact> unusedDeclared )
  {
    if ( !( usedUndeclared.isEmpty() && unusedDeclared.isEmpty() ) )
    {
      File pomFile = getProject().getFile();
      List<String> pomLines = readLines( pomFile );

      // rebuild model from disk in case the pom.xml has been modified since the build started
      List<Dependency> dependencies = rebuildModel( pomLines ).getDependencies();

      if ( !usedUndeclared.isEmpty() )
      {
        addUsedDependencies( dependencies, usedUndeclared, pomLines );

        // rebuild model again, since adding dependencies may have changed line numbers
        dependencies = rebuildModel( pomLines ).getDependencies();
      }

      if ( !unusedDeclared.isEmpty() )
      {
        removeUnusedDependencies( dependencies, unusedDeclared, pomLines );
      }

      getLog().info( "Writing updated POM to " + pomFile );
      writeLines( pomFile, pomLines );

      // Store the updated deps in the plugin context so the analyze mojo can access it
      getPluginContext().put( DEPENDENCY_OVERRIDES, updatedDependencies( usedUndeclared, unusedDeclared ) );
    }
  }

  private Set<Artifact> updatedDependencies( Set<Artifact> usedUndeclared, Set<Artifact> unusedDeclared )
  {
    Set<String> keysToRemove = new HashSet<String>();
    for ( Artifact unused : unusedDeclared )
    {
      keysToRemove.add( unused.getDependencyConflictId() );
    }

    // for some reason removeAll( unusedDeclared ) doesn't work
    Set<Artifact> updated = new HashSet<Artifact>();
    for ( Artifact artifact : getProject().getDependencyArtifacts() )
    {
      if ( !keysToRemove.contains( artifact.getDependencyConflictId() ) )
      {
        updated.add( artifact );
      }
    }

    updated.addAll( usedUndeclared );

    return updated;
  }

  private void removeUnusedDependencies( List<Dependency> dependencies, Set<Artifact> removals, List<String> pomLines )
  {
    String pomLocation = getProject().getFile().toString();

    Set<String> keysToRemove = new HashSet<String>();
    for ( Artifact removal : removals )
    {
      keysToRemove.add( removal.getDependencyConflictId() );
    }

    for ( Dependency dependency : sortByLineNumberDescending( dependencies ) )
    {
      if ( keysToRemove.contains( dependency.getManagementKey() ) )
      {
        InputLocation inputLocation = dependency.getLocation( "" );
        InputSource inputSource = inputLocation.getSource();
        String dependencySource = inputSource == null ? null : inputSource.getLocation();
        if ( !pomLocation.equals( dependencySource ) )
        {
          getLog().warn( "Unable to fix dependency because it comes from parent: " + dependencySource );
        }
        else
        {
          int lineIndex = inputLocation.getLineNumber() - 1; // line numbers start at 1
          getLog().debug( "Starting removal of " + dependency.toString() + " at index " + lineIndex );

          while ( !pomLines.get( lineIndex ).contains( "</dependency>" ) )
          {
            getLog().debug( "Removing line " + pomLines.get( lineIndex ) );
            pomLines.remove( lineIndex );
          }

          // remove that last </dependency> line
          getLog().debug( "Removing line " + pomLines.get( lineIndex ) );
          pomLines.remove( lineIndex );
        }
      }
    }
  }

  private void addUsedDependencies( List<Dependency> dependencies, Set<Artifact> additions, List<String> pomLines )
  {
    List<Dependency> localDependencies = excludeParentDeps( dependencies );

    if ( localDependencies.isEmpty() )
    {
      // TODO create our own dependencies section if none found
      throw new RuntimeException( "No dependencies section found" );
    }

    List<Dependency> testDependencies = consecutiveTestDeps( localDependencies );
    List<Dependency> nonTestDependencies = consecutiveNonTestDeps( localDependencies );

    final int backupTestIndex;
    if ( testDependencies.isEmpty() )
    {
      Dependency lastDep = sortByLineNumberDescending( nonTestDependencies ).get( 0 );
      backupTestIndex = lineIndexAfter( lastDep, pomLines );
    }
    else
    {
      Dependency firstTestDep = sortByLineNumberAscending( testDependencies ).get( 0 );
      backupTestIndex = startIndex( firstTestDep );
    }

    final int backupNonTestIndex;
    if ( nonTestDependencies.isEmpty() )
    {
      Dependency firstTestDep = sortByLineNumberAscending( testDependencies ).get( 0 );
      backupNonTestIndex = startIndex( firstTestDep );
    }
    else
    {
      Dependency firstNonTestDep = sortByLineNumberAscending( nonTestDependencies ).get( 0 );
      backupNonTestIndex = startIndex( firstNonTestDep );
    }

    // add test deps first to maintain line numbers since they go at the bottom
    addDependencies( onlyTestScoped( additions ), pomLines, testDependencies, backupTestIndex );
    addDependencies( notTestScoped( additions ), pomLines, nonTestDependencies, backupNonTestIndex );
  }

  private void addDependencies( Set<Artifact> additions,
                                List<String> pomLines,
                                List<Dependency> existing,
                                int backupIndex )
  {
    existing = sortByLineNumberDescending( existing );

    for ( Artifact addition : sortByCoordinatesDescending( additions ) )
    {
      boolean inserted = false;

      List<Dependency> matchingGroupId = filterConsecutiveGroupId( existing, addition.getGroupId() );
      List<Dependency> toSearch = matchingGroupId.isEmpty() ? existing : matchingGroupId;

      for ( Dependency dependency : toSearch )
      {
        int comparison = dependency.getManagementKey().compareTo( addition.getDependencyConflictId() );
        if ( comparison < 0 )
        {
          int index = lineIndexAfter( dependency, pomLines );
          insertDependency( addition, index, pomLines );
          inserted = true;
          break;
        }
      }

      if ( !inserted )
      {
        final int insertIndex;
        if ( matchingGroupId.isEmpty() )
        {
          insertIndex = backupIndex;
        }
        else
        {
          Dependency firstInGroup = sortByLineNumberAscending( matchingGroupId ).get( 0 );
          insertIndex = startIndex( firstInGroup );
        }

        insertDependency( addition, insertIndex, pomLines );
      }
    }
  }

  private void insertDependency( Artifact addition, int index, List<String> pomLines )
  {
    List<String> lines = toDependencyLines( addition );
    pomLines.addAll( index, lines );
  }

  private Model rebuildModel( List<String> pomLines )
  {
    String pom = StringUtils.join( pomLines, '\n' );
    ModelSource modelSource = new StringModelSource( pom, getProject().getFile().getPath() );
    InputSource inputSource = new InputSource();

    Map<String, Object> options = new HashMap<String, Object>();
    options.put( ModelProcessor.IS_STRICT, true );
    options.put( ModelProcessor.INPUT_SOURCE, inputSource );
    options.put( ModelProcessor.SOURCE, modelSource );

    final Model model;
    try
    {
      model = modelReader.read( modelSource.getInputStream(), options );
    }
    catch ( IOException e )
    {
      throw new RuntimeException( e );
    }

    inputSource.setModelId( getProject().getModel().getId() );
    inputSource.setLocation( getProject().getFile().getAbsolutePath() );

    return model;
  }

  private List<Dependency> excludeParentDeps( List<Dependency> unfiltered )
  {
    String pomLocation = getProject().getFile().toString();
    List<Dependency> filtered = new ArrayList<Dependency>();

    for ( Dependency dependency : unfiltered )
    {
      InputLocation inputLocation = dependency.getLocation( "" );
      InputSource inputSource = inputLocation.getSource();
      String dependencySource = inputSource == null ? null : inputSource.getLocation();
      if ( pomLocation.equals( dependencySource ) )
      {
        filtered.add( dependency );
      }
    }

    return filtered;
  }

  private static int lineIndexAfter( Dependency dependency, List<String> pomLines )
  {
    int lineIndex = startIndex( dependency );

    while ( !pomLines.get( lineIndex ).contains( "</dependency>" ) )
    {
      lineIndex++;
    }

    // advance past that last </dependency> line
    return lineIndex + 1;
  }

  /**
   * Returns continuous sequence of test deps from the end of the pom, stops as soon
   * as it hits a non-test dep
   */
  private static List<Dependency> consecutiveTestDeps( List<Dependency> dependencies )
  {
    List<Dependency> testDependencies = new ArrayList<Dependency>();

    for ( Dependency dependency : sortByLineNumberDescending( dependencies ) )
    {
      if ( Artifact.SCOPE_TEST.equals( dependency.getScope() ) )
      {
        testDependencies.add( dependency );
      }
      else
      {
        break;
      }
    }

    return testDependencies;
  }

  /**
   * Returns continuous sequence of non-test deps from the start of the pom, stops as soon
   * as it hits a test dep
   */
  private static List<Dependency> consecutiveNonTestDeps( List<Dependency> dependencies )
  {
    List<Dependency> nonTestDependencies = new ArrayList<Dependency>();

    for ( Dependency dependency : sortByLineNumberAscending( dependencies ) )
    {
      if ( Artifact.SCOPE_TEST.equals( dependency.getScope() ) )
      {
        break;
      }
      else
      {
        nonTestDependencies.add( dependency );
      }
    }

    return nonTestDependencies;
  }

  private static Set<Artifact> onlyTestScoped( Set<Artifact> artifacts )
  {
    Set<Artifact> filtered = new HashSet<Artifact>();
    for ( Artifact artifact : artifacts )
    {
      if ( Artifact.SCOPE_TEST.equals( artifact.getScope() ) )
      {
        filtered.add( artifact );
      }
    }

    return filtered;
  }

  private static Set<Artifact> notTestScoped( Set<Artifact> artifacts )
  {
    Set<Artifact> filtered = new HashSet<Artifact>( artifacts );
    filtered.removeAll( onlyTestScoped( artifacts ) );
    return filtered;
  }

  /**
   * Returns the first consecutive sequence of deps that match groupId
   */
  private static List<Dependency> filterConsecutiveGroupId( List<Dependency> unfiltered, String groupId )
  {
    List<Dependency> filtered = new ArrayList<Dependency>();
    boolean startedMatching = false;

    for ( Dependency dependency : unfiltered )
    {
      if ( groupId.equals( dependency.getGroupId() ) )
      {
        filtered.add( dependency );
        startedMatching = true;
      }
      else if ( startedMatching )
      {
        // the consecutive sequence has ended, break out
        break;
      }
    }

    return filtered;
  }

  private List<String> toDependencyLines( Artifact artifact )
  {
    // needed to work around MNG-2961
    artifact.isSnapshot();

    List<String> lines = new ArrayList<String>();
    lines.add( "    <dependency>" );
    lines.add( "      <groupId>" + artifact.getGroupId() + "</groupId>" );
    lines.add( "      <artifactId>" + artifact.getArtifactId() + "</artifactId>" );
    if ( !getManagedDependencies().contains( artifact.getDependencyConflictId() ) )
    {
      lines.add( "      <version>" + artifact.getBaseVersion() + "</version>" );
    }
    if ( !StringUtils.isBlank( artifact.getClassifier() ) )
    {
      lines.add( "      <classifier>" + artifact.getClassifier() + "</classifier>" );
    }
    if ( !Artifact.SCOPE_COMPILE.equals( artifact.getScope() ) )
    {
      lines.add( "      <scope>" + artifact.getScope() + "</scope>" );
    }
    lines.add( "    </dependency>" );

    return lines;
  }

  private static List<String> readLines( File file )
  {
    try
    {
      return FileUtils.readLines( file, Charsets.UTF_8 );
    }
    catch ( IOException e )
    {
      throw new RuntimeException( e );
    }
  }

  private static void writeLines( File file, List<String> lines )
  {
    try
    {
      FileUtils.writeLines( file, CharsetNames.UTF_8, lines );
    }
    catch ( IOException e )
    {
      throw new RuntimeException( e );
    }
  }

  private static List<Dependency> sortByLineNumberAscending( List<Dependency> unsorted )
  {
    Comparator<Dependency> lineNumberComparator = new Comparator<Dependency>()
    {

      @Override
      public int compare( Dependency dep1, Dependency dep2 )
      {
        Integer line1 = startIndex( dep1 );
        Integer line2 = startIndex( dep2 );

        return line1.compareTo( line2 );
      }
    };

    List<Dependency> sorted = new ArrayList<Dependency>( unsorted );
    Collections.sort( sorted, lineNumberComparator );
    return sorted;
  }

  private static List<Dependency> sortByLineNumberDescending( List<Dependency> unsorted )
  {
    List<Dependency> ascending = new ArrayList<Dependency>( sortByLineNumberAscending( unsorted ) );
    Collections.reverse( ascending );
    return ascending;
  }

  /**
   * Test-scoped deps go at the bottom of the <dependencies> section, so sort
   * these first to prevent throwing off line numbers at the top. Same idea
   * for the secondary, descending sort by coordinates.
   */
  private static List<Artifact> sortByCoordinatesDescending( Set<Artifact> unsorted )
  {
    Comparator<Artifact> coordinatesComparator = new Comparator<Artifact>()
    {

      @Override
      public int compare( Artifact artifact1, Artifact artifact2 )
      {
        String key1 = artifact1.getDependencyConflictId();
        String key2 = artifact2.getDependencyConflictId();

        return key1.compareTo( key2 ) * -1;
      }
    };

    List<Artifact> sorted = new ArrayList<Artifact>( unsorted );
    Collections.sort( sorted, coordinatesComparator );
    return sorted;
  }

  private static int startIndex( Dependency dependency )
  {
    // line numbers start at 1, but we want it 0-indexed
    return dependency.getLocation( "" ).getLineNumber() - 1;
  }
}
