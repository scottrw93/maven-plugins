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
import java.util.List;
import java.util.Set;

import org.apache.commons.compress.utils.CharsetNames;
import org.apache.commons.compress.utils.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

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
@Execute( phase = LifecyclePhase.TEST_COMPILE )
public class FixMojo
    extends AbstractAnalyzeMojo
{

  @Override
  protected boolean isSkip()
  {
    return false;
  }

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
      MavenProject project = getProject();
      String pomLocation = project.getFile().toString();
      List<String> pomLines = readLines( project.getFile() );

      // process removals before additions to preserve line numbers
      for ( Artifact unused : unusedDeclared )
      {
        String unusedKey = unused.getDependencyConflictId();

        for ( Dependency dependency : sortByLineNumberDescending( project.getModel().getDependencies() ) )
        {
          if ( unusedKey.equals( dependency.getManagementKey() ) )
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

              while ( !pomLines.get( lineIndex ).contains( "</dependency>" ) )
              {
                pomLines.remove( lineIndex );
              }

              pomLines.remove( lineIndex ); // remove that last </dependency> line
            }
          }
        }
      }

      int startDependencies = findStartDependenciesIndex( pomLines );
      int endDependencies = findEndDependenciesIndex( pomLines, startDependencies );
      Set<String> managedDependencies = getManagedDependencies();
      for ( Artifact undeclared : sortByTestScopeFirst( usedUndeclared ) )
      {
        // needed to work around MNG-2961
        undeclared.isSnapshot();

        List<String> newLines = new ArrayList<String>();
        newLines.add( "    <dependency>" );
        newLines.add( "      <groupId> " + undeclared.getGroupId() + "</groupId" );
        newLines.add( "      <artifactId>" + undeclared.getArtifactId() + "</artifactId>" );
        if ( !managedDependencies.contains( undeclared.getDependencyConflictId() ) )
        {
          newLines.add( "      <version>" + undeclared.getBaseVersion() + "</version>" );
        }
        if ( !StringUtils.isBlank( undeclared.getClassifier() ) )
        {
          newLines.add( "      <classifier>" + undeclared.getClassifier() + "</classifier>" );
        }
        if ( !Artifact.SCOPE_COMPILE.equals( undeclared.getScope() ) )
        {
          newLines.add( "      <scope>" + undeclared.getScope() + "</scope>" );
        }
        newLines.add( "    </dependency>" );

        // add test-scoped deps at the bottom
        if ( Artifact.SCOPE_TEST.equals( undeclared.getScope() ) )
        {
          pomLines.addAll( endDependencies, newLines );
        }
        else
        {
          pomLines.addAll( startDependencies + 1, newLines );
        }
      }

      getLog().info( "Writing updated POM to " + project.getFile() );
      writeLines( project.getFile(), pomLines );
    }
  }

  private static int findStartDependenciesIndex( List<String> pomLines )
  {
    boolean inDependencyManagement = false;
    for ( int i = 0; i < pomLines.size(); i++ )
    {
      String line = pomLines.get( i );

      if ( line.contains( "<dependencyManagement>" ) )
      {
        inDependencyManagement = true;
      }

      if ( line.contains( "</dependencyManagement>" ) )
      {
        inDependencyManagement = false;
      }

      if ( line.contains( "<dependencies>" ) )
      {
        if ( !inDependencyManagement )
        {
          return i;
        }
      }
    }

    // TODO create our own dependencies section if none found
    throw new RuntimeException( "No dependencies section found" );
  }

  private static int findEndDependenciesIndex( List<String> pomLines, int startDependencies )
  {
    for ( int i = startDependencies; i < pomLines.size(); i++ )
    {
      String line = pomLines.get( i );

      if ( line.contains( "</dependencies>" ) )
      {
        return i;
      }
    }

    throw new RuntimeException( "Couldn't find end of dependencies section" );
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

  /**
   * Sorts dependencies in reverse order of line number. We want to remove
   * dependencies starting from the bottom as to not throw off subsequent
   * line numbers
   */
  private static List<Dependency> sortByLineNumberDescending( List<Dependency> unsorted )
  {
    Comparator<Dependency> lineNumberComparator = new Comparator<Dependency>()
    {

      @Override
      public int compare( Dependency dep1, Dependency dep2 )
      {
        InputLocation location1 = dep1.getLocation( "" );
        int line1 = location1 == null ? 0 : location1.getLineNumber();

        InputLocation location2 = dep2.getLocation( "" );
        int line2 = location2 == null ? 0 : location2.getLineNumber();

        if ( line1 == line2 )
        {
          return 0;
        }
        else if ( line1 < line2 )
        {
          return 1;
        }
        else
        {
          return -1;
        }
      }
    };

    List<Dependency> sorted = new ArrayList<Dependency>( unsorted );
    Collections.sort( sorted, lineNumberComparator );
    return sorted;
  }

  /**
   * Test-scoped deps go at the bottom of the <dependencies> section, so sort
   * these first to prevent throwing off line numbers at the top
   */
  private static List<Artifact> sortByTestScopeFirst( Set<Artifact> unsorted )
  {
    Comparator<Artifact> testScopeComparator = new Comparator<Artifact>()
    {

      @Override
      public int compare( Artifact artifact1, Artifact artifact2 )
      {
        String scope1 = artifact1.getScope() == null ? "compile" : artifact1.getScope();
        String scope2 = artifact2.getScope() == null ? "compile" : artifact2.getScope();
        if ( Artifact.SCOPE_TEST.equals( scope1 ) && Artifact.SCOPE_TEST.equals( scope2 ) )
        {
          return 0;
        }
        else if ( Artifact.SCOPE_TEST.equals( scope1 ) )
        {
          return -1;
        }
        else if ( Artifact.SCOPE_TEST.equals( scope2 ) )
        {
          return 1;
        }
        else
        {
          return scope1.compareTo( scope2 );
        }
      }
    };

    List<Artifact> sorted = new ArrayList<Artifact>( unsorted );
    Collections.sort( sorted, testScopeComparator );
    return sorted;
  }
}
