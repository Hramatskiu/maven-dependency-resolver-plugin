package com.pentaho.maven.plugin.resolver;

public class ResolverFilter {
    private String include;
    private String exclude;
    private Boolean transitive;

    public String getInclude() {
        return include;
    }

    public String getExclude() {
        return exclude;
    }

    public Boolean getTransitive() {
        return transitive;
    }
}
