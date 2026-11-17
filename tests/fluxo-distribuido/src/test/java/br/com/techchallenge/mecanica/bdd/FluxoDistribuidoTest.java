package br.com.techchallenge.mecanica.bdd;
import org.junit.platform.suite.api.*;
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key="cucumber.glue", value="br.com.techchallenge.mecanica.bdd")
@ConfigurationParameter(key="cucumber.plugin", value="pretty,html:target/cucumber/fluxo.html,json:target/cucumber/fluxo.json")
public class FluxoDistribuidoTest { }
