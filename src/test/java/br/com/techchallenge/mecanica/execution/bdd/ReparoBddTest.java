package br.com.techchallenge.mecanica.execution.bdd;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = "cucumber.glue", value = "br.com.techchallenge.mecanica.execution.bdd")
@ConfigurationParameter(key = "cucumber.plugin", value = "pretty,html:target/cucumber/reparos.html,json:target/cucumber/reparos.json")
public class ReparoBddTest { }
