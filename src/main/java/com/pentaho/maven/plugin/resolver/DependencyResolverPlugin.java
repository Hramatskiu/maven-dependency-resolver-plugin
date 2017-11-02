package com.pentaho.maven.plugin.resolver;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.*;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.artifact.filter.PatternExcludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.PatternIncludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.StatisticsReportingArtifactFilter;
import org.apache.maven.shared.artifact.filter.resolve.ScopeFilter;
import org.apache.maven.shared.artifact.filter.resolve.transform.ArtifactIncludeFilterTransformer;
import org.apache.maven.shared.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.dependencies.resolve.DependencyResolverException;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

import java.util.*;

@Mojo( name = "resolve", inheritByDefault = false, requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true )
public class DependencyResolverPlugin extends AbstractMojo {
//    @Parameter( defaultValue = "")
//    private ResolverFilter resolverFilter;
    @Parameter( defaultValue = "")
    private List resolverFilters;

    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession mavenSession;
    /**
     * Local Maven repository where artifacts are cached during the build process.
     */
    @Parameter( defaultValue = "${localRepository}", required = true, readonly = true )
    private ArtifactRepository localRepository;

    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    @Parameter( defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true )
    private List<ArtifactRepository> remoteRepositories;

    @Requirement
    private org.apache.maven.shared.dependencies.resolve.DependencyResolver dependencyResolver;
    @Component
    private RepositorySystem resolver;
    @Component
    private ProjectBuilder projectBuilder;

    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Started");
        try {
            resolveDependencies();
        } catch (ResolverException e) {
            throw new MojoFailureException("Can't resolve dependencies. ", e);
        }

        Set<Artifact> finalArtifacts = new HashSet<Artifact>();

        for ( Object object : resolverFilters ) {
            List<String> ids = new ArrayList<String>();
            Set<Artifact> resolvedArtifacts = new HashSet<Artifact>();
            Set<Artifact> transitiveArtifacts = new HashSet<Artifact>();

            ResolverFilter resolverFilter = (ResolverFilter) object;

            for ( Artifact artifact : project.getArtifacts() ) {
                for ( String include : resolverFilter.getInclude().split( "," ) ) {
                    if ( isExcluded( artifact, splitExclude( include ) ) ) {
                        transitiveArtifacts.add( artifact );
                    }
                }
            }

            if ( resolverFilter.getTransitive() ) {
                resolvedArtifacts.addAll( resolveTransitively( transitiveArtifacts ).getArtifacts() );
            }
            else {
                resolvedArtifacts.addAll( resolveNonTransitively( transitiveArtifacts ) );
            }

            List<Artifact> artifactsToExclude = new ArrayList<Artifact>();

//            for ( Artifact artifact : resolvedArtifacts ) {
//
//                boolean isInclude = false;
//
//                for( String include : resolverFilter.getInclude().split( "," ) ) {
//                    for ( String trail : artifact.getDependencyTrail() ) {
//                        if ( isIncludeTrail( trail, splitExclude( include ) ) ) {
//                            isInclude = true;
//                        }
//                    }
//                }
//
//                for( String exclude : resolverFilter.getExclude().split( "," ) ) {
//                    if ( isExcluded( artifact, splitExclude( exclude ) ) ) {
//                        isInclude = false;
//                    }
//                }
//
//                for( String exclude : resolverFilter.getExclude().split( "," ) ) {
//                    for ( String trail : artifact.getDependencyTrail() ) {
//                        if ( isIncludeTrail( trail, splitExclude( exclude ) ) ) {
//                            isInclude = false;
//                        }
//                    }
//                }
//
//                for( String include : resolverFilter.getInclude().split( "," ) ) {
//                    if ( isExcluded( artifact, splitExclude( include ) ) ) {
//                        isInclude = true;
//                    }
//                }
//
//                if ( !isInclude ) {
//                    artifactsToExclude.add( artifact );
//                }
//            }
//
//            for ( Artifact artifact : artifactsToExclude ) {
//                resolvedArtifacts.remove( artifact );
//            }

            filterArtifacts( resolvedArtifacts, Arrays.asList( resolverFilter.getInclude().split( "," ) ),
                    Arrays.asList( resolverFilter.getExclude().split( "," ) ),
                    resolverFilter.getTransitive() );

            for (Artifact artifact : resolvedArtifacts) {
                ids.add( artifact.getArtifactId() + ":" + artifact.getVersion() );
            }
            Collections.sort(ids);
            for (String id : ids) {
                getLog().info( id );
            }

            for ( Artifact artifact : resolvedArtifacts ) {
                if ( !finalArtifacts.contains( artifact ) ) {
                    finalArtifacts.add( artifact );
                }
            }
        }

        project.setDependencyArtifacts( finalArtifacts );
    }

    private static void filterArtifacts( final Set<Artifact> artifacts, final List<String> includes,
                                        final List<String> excludes, final boolean actTransitively ) {
        //final List<ArtifactFilter> allFilters = new ArrayList<ArtifactFilter>();

        final AndArtifactFilter filter = new AndArtifactFilter();

        filter.add( new ArtifactIncludeFilterTransformer().transform( ScopeFilter.including( "compile", "runtime" ) ) );

        if ( !includes.isEmpty() )
        {
            final ArtifactFilter includeFilter = new PatternIncludesArtifactFilter( includes, actTransitively );

            filter.add( includeFilter );

            //allFilters.add( includeFilter );
        }

        if ( !excludes.isEmpty() )
        {
            final ArtifactFilter excludeFilter = new PatternExcludesArtifactFilter( excludes, actTransitively );

            filter.add( excludeFilter );

            //allFilters.add( excludeFilter );
        }

        // FIXME: Why is this added twice??
        // if ( additionalFilters != null && !additionalFilters.isEmpty() )
        // {
        // allFilters.addAll( additionalFilters );
        // }

        for ( final Iterator<Artifact> it = artifacts.iterator(); it.hasNext(); )
        {
            final Artifact artifact = it.next();

            if ( !filter.include( artifact ) )
            {
                it.remove();
            }
        }
    }


    private boolean isIncludeTrail( String trail, String[] include ) {
        return isExcluded( trail.split( ":" )[0], include[0] ) && isExcluded( trail.split( ":" )[1], include[1] )
          && include[3].equals( "*" );
    }

    private String[] splitExclude( String exclude ) {
        switch ( exclude.split( ":" ).length ) {
            case 1 : return splitGroupId( exclude );
            case 2 : return splitGroupAndArtifactId( exclude.split(":") );
            case 3 : return splitGroupAndArtifactAndTypeId( exclude.split(":") );
            default: return exclude.split(":");
        }
    }

    private String[] splitGroupId( String groupId ) {
        return new String[]{ groupId, "*", "*", "*" };
    }

    private String[] splitGroupAndArtifactId( String[] split ) {
        return new String[]{ split[0], split[1], "*", "*" };
    }

    private String[] splitGroupAndArtifactAndTypeId( String[] split ) {
        return new String[]{ split[0], split[1], split[2], "*" };
    }

    private boolean isExcluded( Artifact artifact, String[] splitedExclude ) {
        return isExcluded( artifact.getGroupId(), splitedExclude[0].trim() ) && isExcluded( artifact.getArtifactId(), splitedExclude[1].trim() )
          && isExcluded( artifact.getType(), splitedExclude[2].trim() ) && isExcluded( artifact.getClassifier(), splitedExclude[3].trim() );
    }

    private boolean isExcluded( String checkString, String excludeString ) {
        return excludeString.equals( "*" ) || excludeString.equals( checkString );
    }

    private void resolveDependencies() throws ResolverException {
        Set<Artifact> dependencyArtifacts = project.getDependencyArtifacts();
        if ( dependencyArtifacts == null )
        {
            try
            {
                ProjectBuildingRequest pbr = new DefaultProjectBuildingRequest( mavenSession.getProjectBuildingRequest() );
                pbr.setRemoteRepositories( project.getRemoteArtifactRepositories() );
                Iterable<ArtifactResult> artifactResults =
                        dependencyResolver.resolveDependencies( pbr, project.getModel(), ScopeFilter.including("compile", "runtime"));

                dependencyArtifacts = new HashSet<Artifact>();

                for ( ArtifactResult artifactResult : artifactResults )
                {
                    getLog().info("Resolve artifact dependency - " + artifactResult.getArtifact().getArtifactId());
                    dependencyArtifacts.add( artifactResult.getArtifact() );
                }

                project.setDependencyArtifacts( dependencyArtifacts );
            }
            catch ( final DependencyResolverException e )
            {
                throw new ResolverException("Failed to create dependency artifacts for resolution. Assembly: " , e );
            }
        }
    }

    private ArtifactResolutionResult resolveTransitively( Set<Artifact> dependencyArtifacts ) {
//        final ServiceLocator serviceLocator = MavenRepositorySystemUtils.newServiceLocator();
//        final RepositorySystem resolver = serviceLocator.getService(RepositorySystem.class);

        ArtifactResolutionRequest req = new ArtifactResolutionRequest();
        req.setLocalRepository( localRepository );
        req.setResolveRoot( false );
        req.setRemoteRepositories( aggregateRemoteArtifactRepositories() );
        req.setResolveTransitively( true );
        req.setArtifact( project.getArtifact() );
        req.setArtifactDependencies( dependencyArtifacts );
        req.setManagedVersionMap( project.getManagedVersionMap() );
        req.setCollectionFilter( new ArtifactIncludeFilterTransformer().transform( ScopeFilter.including( "compile", "runtime" ) ) );
        req.setOffline( mavenSession.isOffline() );
        req.setForceUpdate( mavenSession.getRequest().isUpdateSnapshots() );
        req.setServers( mavenSession.getRequest().getServers() );
        req.setMirrors( mavenSession.getRequest().getMirrors() );
        req.setProxies( mavenSession.getRequest().getProxies() );

        ArtifactResolutionResult result;

        result = resolver.resolve( req );

        return result;
    }

    private List<ArtifactRepository> aggregateRemoteArtifactRepositories( ) {
        final List<List<ArtifactRepository>> repoLists = new ArrayList<List<ArtifactRepository>>();

        repoLists.add( remoteRepositories );
        repoLists.add( project.getRemoteArtifactRepositories() );

        final List<ArtifactRepository> remoteRepos = new ArrayList<ArtifactRepository>();
        final Set<String> encounteredUrls = new HashSet<String>();

        for ( final List<ArtifactRepository> repositoryList : repoLists )
        {
            if ( ( repositoryList != null ) && !repositoryList.isEmpty() )
            {
                for ( final ArtifactRepository repo : repositoryList )
                {
                    if ( !encounteredUrls.contains( repo.getUrl() ) )
                    {
                        remoteRepos.add( repo );
                        encounteredUrls.add( repo.getUrl() );
                    }
                }
            }
        }

        return remoteRepos;
    }

    private Set<Artifact> resolveNonTransitively( Set<Artifact> dependencyArtifacts ) {
        Set<Artifact> resolved = new HashSet<Artifact>();

        for ( final Artifact depArtifact : dependencyArtifacts )
        {
            ArtifactResolutionRequest req = new ArtifactResolutionRequest();
            req.setLocalRepository( localRepository );
            req.setRemoteRepositories( remoteRepositories );
            req.setArtifact( depArtifact );

            ArtifactResolutionResult resolve = resolver.resolve( req );
            if ( resolve.hasExceptions() )
            {
                getLog().error( "Can't resolve artifact - " + depArtifact.getArtifactId() );
            }
            else
            {
                resolved.add( depArtifact );
            }
        }

        return resolved;
    }

    private void resolveRecursively(Set<Artifact> artifacts) {
        try {
            for (Artifact artifact : artifacts) {
                if ( artifact.getScope().equals( "compile" ) ) {
                    ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());
                    buildingRequest.setProject(null);
                    MavenProject mavenProject = projectBuilder.build(artifact, buildingRequest).getProject();

                    this.resolveRecursively( mavenProject.getDependencyArtifacts() );
                }
            }
        } catch (ProjectBuildingException e) {
            e.printStackTrace();
        }
    }
}
