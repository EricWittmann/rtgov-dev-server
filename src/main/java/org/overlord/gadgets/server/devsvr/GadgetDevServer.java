/*
 * Copyright 2013 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.overlord.gadgets.server.devsvr;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumSet;

import javax.naming.InitialContext;
import javax.servlet.DispatcherType;
import javax.sql.DataSource;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.shindig.auth.AuthenticationServletFilter;
import org.apache.shindig.common.servlet.GuiceServletContextListener;
import org.apache.shindig.common.servlet.HostFilter;
import org.apache.shindig.gadgets.servlet.ConcatProxyServlet;
import org.apache.shindig.gadgets.servlet.ETagFilter;
import org.apache.shindig.gadgets.servlet.GadgetRenderingServlet;
import org.apache.shindig.gadgets.servlet.HtmlAccelServlet;
import org.apache.shindig.gadgets.servlet.JsServlet;
import org.apache.shindig.gadgets.servlet.MakeRequestServlet;
import org.apache.shindig.gadgets.servlet.OAuth2CallbackServlet;
import org.apache.shindig.gadgets.servlet.OAuthCallbackServlet;
import org.apache.shindig.gadgets.servlet.ProxyServlet;
import org.apache.shindig.gadgets.servlet.RpcServlet;
import org.apache.shindig.gadgets.servlet.RpcSwfServlet;
import org.apache.shindig.protocol.DataServiceServlet;
import org.apache.shindig.protocol.JsonRpcServlet;
import org.apache.shindig.social.core.oauth2.OAuth2Servlet;
import org.apache.shindig.social.sample.oauth.SampleOAuthServlet;
import org.apache.shiro.web.servlet.IniShiroFilter;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.h2.Driver;
import org.jboss.resteasy.plugins.guice.GuiceResteasyBootstrapServletContextListener;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.overlord.commons.dev.server.DevServer;
import org.overlord.commons.dev.server.DevServerEnvironment;
import org.overlord.commons.dev.server.MultiDefaultServlet;
import org.overlord.commons.dev.server.discovery.JarModuleFromIDEDiscoveryStrategy;
import org.overlord.commons.dev.server.discovery.JarModuleFromMavenDiscoveryStrategy;
import org.overlord.commons.dev.server.discovery.WebAppModuleFromIDEDiscoveryStrategy;
import org.overlord.commons.dev.server.discovery.WebAppModuleFromIDEGAVStrategy;
import org.overlord.commons.dev.server.discovery.WebAppModuleFromMavenDiscoveryStrategy;
import org.overlord.commons.dev.server.discovery.WebAppModuleFromMavenGAVStrategy;
import org.overlord.commons.ui.header.OverlordHeaderDataJS;
import org.overlord.gadgets.web.server.StoreController;

/**
 * Dev environment bootstrapper for rtgov/bootstrapper.
 * @author eric.wittmann@redhat.com
 */
public class GadgetDevServer extends DevServer {

    private DataSource ds = null;

    /**
     * Main entry point.
     * @param args
     */
    public static void main(String [] args) throws Exception {
        System.setProperty("discovery-strategy.debug", "true");
        GadgetDevServer devServer = new GadgetDevServer(args);
        devServer.go();
    }

    /**
     * Constructor.
     * @param args
     */
    public GadgetDevServer(String [] args) {
        super(args);
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#preConfig()
     */
    @Override
    protected void preConfig() {
        // Add JNDI resources
        try {
            InitialContext ctx = new InitialContext();
            ctx.bind("java:jboss", new InitialContext());
            ds = createInMemoryDatasource();
            ctx.bind("java:jboss/GadgetServer", ds);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#createDevEnvironment()
     */
    @Override
    protected DevServerEnvironment createDevEnvironment() {
        return new GadgetDevServerEnvironment(args);
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#addModules(org.overlord.commons.dev.server.DevServerEnvironment)
     */
    @Override
    protected void addModules(DevServerEnvironment environment) {
        environment.addModule("gadget-server",
                new WebAppModuleFromIDEGAVStrategy("org.overlord.gadgets.server", "gadget-server", false),
                new WebAppModuleFromMavenGAVStrategy("org.overlord.gadgets.server", "gadget-server"));
        environment.addModule("gadgets",
                new WebAppModuleFromIDEGAVStrategy("org.overlord.rtgov", "gadgets", true),
                new WebAppModuleFromMavenGAVStrategy("org.overlord.rtgov", "gadgets"));
        environment.addModule("gadget-web",
                new WebAppModuleFromIDEGAVStrategy("org.overlord.gadgets.server", "gadget-web", true),
                new WebAppModuleFromIDEDiscoveryStrategy(StoreController.class),
                new WebAppModuleFromMavenDiscoveryStrategy(StoreController.class));
        environment.addModule("overlord-commons-uiheader",
                new JarModuleFromIDEDiscoveryStrategy(OverlordHeaderDataJS.class, "src/main/resources/META-INF/resources"),
                new JarModuleFromMavenDiscoveryStrategy(OverlordHeaderDataJS.class, "/META-INF/resources"));
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#addModulesToJetty(org.overlord.commons.dev.server.DevServerEnvironment, org.eclipse.jetty.server.handler.ContextHandlerCollection)
     */
    @Override
    protected void addModulesToJetty(DevServerEnvironment environment, ContextHandlerCollection handlers)
            throws Exception {
        URL[] clURLs = new URL[] {
                new File(environment.getModuleDir("gadget-server"), "WEB-INF/classes").toURI().toURL()
        };
        // Set up the classloader.
        ClassLoader cl = new URLClassLoader(clURLs, GadgetDevServer.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);

        /* *********
         * gadgets
         * ********* */
        ServletContextHandler gadgets = new ServletContextHandler(ServletContextHandler.SESSIONS);
        gadgets.setContextPath("/gadgets");
        gadgets.setResourceBase(environment.getModuleDir("gadgets").getCanonicalPath());
        // File resources
        ServletHolder resources = new ServletHolder(new DefaultServlet());
        resources.setInitParameter("dirAllowed", "true");
        resources.setInitParameter("pathInfoOnly", "false");
        gadgets.addServlet(resources, "/");


        /* *********
         * gadget-server
         * ********* */
        System.setProperty("shindig.host", "");
        System.setProperty("shindig.port", "");
        System.setProperty("aKey", "/shindig/gadgets/proxy?container=default&url=");
        ServletContextHandler gadgetServer = new ServletContextHandler(ServletContextHandler.SESSIONS);
        gadgetServer.setInitParameter("guice-modules", GUICE_MODULES);
        gadgetServer.setContextPath("/gadget-server");
        gadgetServer.setWelcomeFiles(new String[] { "samplecontainer/samplecontainer.html" });
        gadgetServer.setResourceBase(environment.getModuleDir("gadget-server").getCanonicalPath());
        gadgetServer.addEventListener(new GuiceServletContextListener());
        // HostFilter
        gadgetServer.addFilter(HostFilter.class, "/gadgets/ifr", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(HostFilter.class, "/gadgets/js/*", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(HostFilter.class, "/gadgets/proxy/*", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(HostFilter.class, "/gadgets/concat", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(HostFilter.class, "/gadgets/makeRequest", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(HostFilter.class, "/rpc/*", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(HostFilter.class, "/rest/*", EnumSet.of(DispatcherType.REQUEST));
        // ShiroFilter
        FilterHolder shiroFilter = new FilterHolder(IniShiroFilter.class);
        shiroFilter.setInitParameter("config", SHIRO_CONFIG);
        gadgetServer.addFilter(shiroFilter, "/oauth/authorize", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(shiroFilter, "/oauth2/authorize", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(shiroFilter, "*.jsp", EnumSet.of(DispatcherType.REQUEST));
        // AuthFilter
        gadgetServer.addFilter(AuthenticationServletFilter.class, "/gadgets/ifr", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(AuthenticationServletFilter.class, "/gadgets/js/*", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(AuthenticationServletFilter.class, "/gadgets/proxy/*", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(AuthenticationServletFilter.class, "/gadgets/concat", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(AuthenticationServletFilter.class, "/gadgets/makeRequest", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(AuthenticationServletFilter.class, "/rpc/*", EnumSet.of(DispatcherType.REQUEST));
        gadgetServer.addFilter(AuthenticationServletFilter.class, "/rest/*", EnumSet.of(DispatcherType.REQUEST));
        // EtagFilter
        gadgetServer.addFilter(ETagFilter.class, "*", EnumSet.of(DispatcherType.REQUEST));
        // Servlets
        gadgetServer.addServlet(GadgetRenderingServlet.class, "/gadgets/ifr");
        gadgetServer.addServlet(HtmlAccelServlet.class, "/gadgets/accel");
        gadgetServer.addServlet(ProxyServlet.class, "/gadgets/proxy/*");
        gadgetServer.addServlet(MakeRequestServlet.class, "/gadgets/makeRequest");
        gadgetServer.addServlet(ConcatProxyServlet.class, "/gadgets/concat");
        gadgetServer.addServlet(OAuthCallbackServlet.class, "/gadgets/oauthcallback");
        gadgetServer.addServlet(OAuth2CallbackServlet.class, "/gadgets/oauth2callback");
        gadgetServer.addServlet(RpcServlet.class, "/gadgets/metadata");
        gadgetServer.addServlet(JsServlet.class, "/gadgets/js/*");
        ServletHolder servletHolder = new ServletHolder(DataServiceServlet.class);
        servletHolder.setInitParameter("handlers", "org.apache.shindig.handlers");
        gadgetServer.addServlet(servletHolder, "/rest/*");
        gadgetServer.addServlet(servletHolder, "/gadgets/api/rest/*");
        gadgetServer.addServlet(servletHolder, "/social/rest/*");
        servletHolder = new ServletHolder(JsonRpcServlet.class);
        servletHolder.setInitParameter("handlers", "org.apache.shindig.handlers");
        gadgetServer.addServlet(servletHolder, "/rpc/*");
        gadgetServer.addServlet(servletHolder, "/gadgets/api/rpc/*");
        gadgetServer.addServlet(servletHolder, "/social/rpc/*");
        gadgetServer.addServlet(SampleOAuthServlet.class, "/oauth/*");
        gadgetServer.addServlet(OAuth2Servlet.class, "/oauth2/*");
        gadgetServer.addServlet(RpcSwfServlet.class, "/xpc*");
        // Resources
        resources = new ServletHolder(new DefaultServlet());
        resources.setInitParameter("dirAllowed", "true");
        resources.setInitParameter("pathInfoOnly", "false");
        gadgetServer.addServlet(resources, "/");


        /* *********
         * gadget-web
         * ********* */
        ServletContextHandler gadgetWeb = new ServletContextHandler(ServletContextHandler.SESSIONS|ServletContextHandler.SECURITY);
        gadgetWeb.setInitParameter("resteasy.guice.modules", RE_GUICE_MODULES);
        gadgetWeb.setInitParameter("resteasy.servlet.mapping.prefix", "/rs");
        gadgetWeb.setContextPath("/gadget-web");
        gadgetWeb.setWelcomeFiles(new String[] { "Application.html" });
        gadgetWeb.setResourceBase(environment.getModuleDir("gadget-web").getCanonicalPath());
        gadgetWeb.addEventListener(new GuiceResteasyBootstrapServletContextListener());
        gadgetWeb.addServlet(HttpServletDispatcher.class, "/rs/*");
        ServletHolder overlordHeaderJS = new ServletHolder(OverlordHeaderDataJS.class);
        overlordHeaderJS.setInitParameter("app-id", "gadget-server");
        gadgetWeb.addServlet(overlordHeaderJS, "/js/overlord-header-data.js");
        // Resources
        resources = new ServletHolder(new MultiDefaultServlet());
        resources.setInitParameter("resourceBase", "/");
        resources.setInitParameter("resourceBases", environment.getModuleDir("gadget-web").getCanonicalPath()
                + "|" + environment.getModuleDir("overlord-commons-uiheader").getCanonicalPath());
        resources.setInitParameter("dirAllowed", "true");
        resources.setInitParameter("pathInfoOnly", "false");
        gadgetWeb.addServlet(resources, "/");
        gadgetWeb.setSecurityHandler(createSecurityHandler());

        // Add the web contexts to jetty
        handlers.addHandler(gadgets);
        handlers.addHandler(gadgetServer);
        handlers.addHandler(gadgetWeb);
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#postStart(org.overlord.commons.dev.server.DevServerEnvironment)
     */
    @Override
    protected void postStart(DevServerEnvironment environment) throws Exception {
        bootstrapGadgetDB();
        seedDB(ds);
    }

    /**
     * @throws MalformedURLException
     * @throws IOException
     */
    private void bootstrapGadgetDB() throws MalformedURLException, IOException {
        String urlStr = String.format("http://localhost:%1$d/gadget-web/rs/stores/all/0/1", serverPort());

        HttpClient client = new HttpClient();
        client.getState().setCredentials(
            new AuthScope("localhost", serverPort(), "overlordrealm"),
            new UsernamePasswordCredentials("admin", "admin")
        );

        GetMethod get = new GetMethod(urlStr);
        get.setDoAuthentication( true );
        try {
            int status = client.executeMethod( get );
            if (status != 200) {
                throw new RuntimeException("Error bootstrapping DB: " + status);
            }
        } finally {
            get.releaseConnection();
        }

//        URL url = new URL(urlStr);
//        URLConnection conn = url.openConnection();
//        conn.getInputStream().close();
    }

    /**
     * Creates a basic auth security handler.
     */
    private SecurityHandler createSecurityHandler() {
        HashLoginService l = new HashLoginService();
        for (String user : USERS) {
            l.putUser(user, Credential.getCredential(user), new String[] {"user"});
        }
        l.setName("overlordrealm");

        Constraint constraint = new Constraint();
        constraint.setName(Constraint.__BASIC_AUTH);
        constraint.setRoles(new String[]{"user"});
        constraint.setAuthenticate(true);

        ConstraintMapping cm = new ConstraintMapping();
        cm.setConstraint(constraint);
        cm.setPathSpec("/*");

        ConstraintSecurityHandler csh = new ConstraintSecurityHandler();
        csh.setAuthenticator(new BasicAuthenticator());
        csh.setRealmName("overlordrealm");
        csh.addConstraintMapping(cm);
        csh.setLoginService(l);

        return csh;
    }

    /**
     * Creates an in-memory datasource.
     * @throws SQLException
     */
    private static DataSource createInMemoryDatasource() throws SQLException {
        System.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName(Driver.class.getName());
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
        Connection connection = ds.getConnection();
        connection.close();
        return ds;
    }

    /**
     * @param ds
     * @throws SQLException
     * @throws IOException
     */
    private void seedDB(DataSource ds) throws SQLException, IOException {
        Connection connection = ds.getConnection();

        try {
            String sql = DB_SEED_DATA;
            BufferedReader reader = new BufferedReader(new StringReader(sql));
            String line = null;
            while ( (line = reader.readLine()) != null) {
                if (line.trim().length() > 0) {
                    System.out.println(" DB> " + line);
                    connection.createStatement().execute(line);
                }
            }

            connection.commit();
        } finally {
            connection.close();
        }
    }

    private static final String GUICE_MODULES = "" +
            "            org.apache.shindig.common.PropertiesModule:\r\n" +
            "            org.apache.shindig.gadgets.DefaultGuiceModule:\r\n" +
            "            org.apache.shindig.social.core.config.SocialApiGuiceModule:\r\n" +
            "            org.apache.shindig.social.sample.SampleModule:\r\n" +
            "            org.apache.shindig.gadgets.oauth.OAuthModule:\r\n" +
            "            org.apache.shindig.gadgets.oauth2.OAuth2Module:\r\n" +
            "            org.apache.shindig.gadgets.oauth2.OAuth2MessageModule:\r\n" +
            "            org.apache.shindig.gadgets.oauth2.handler.OAuth2HandlerModule:\r\n" +
            "            org.apache.shindig.gadgets.oauth2.persistence.sample.OAuth2PersistenceModule:\r\n" +
            "            org.apache.shindig.common.cache.ehcache.EhCacheModule:\r\n" +
            "            org.apache.shindig.sample.shiro.ShiroGuiceModule:\r\n" +
            "            org.apache.shindig.sample.container.SampleContainerGuiceModule:\r\n" +
            "            org.apache.shindig.extras.ShindigExtrasGuiceModule:\r\n" +
            "            org.apache.shindig.gadgets.admin.GadgetAdminModule";

    private static final String SHIRO_CONFIG = "" +
            "                # The ShiroFilter configuration is very powerful and flexible, while still remaining succinct.\r\n" +
            "                # Please read the comprehensive example, with full comments and explanations, in the JavaDoc:\r\n" +
            "                #\r\n" +
            "                # http://www.jsecurity.org/api/org/jsecurity/web/servlet/JSecurityFilter.html\r\n" +
            "                [main]\r\n" +
            "                shindigSampleRealm = org.apache.shindig.sample.shiro.SampleShiroRealm\r\n" +
            "                securityManager.realm = $shindigSampleRealm\r\n" +
            "                authc.loginUrl = /login.jsp\r\n" +
            "\r\n" +
            "                [urls]\r\n" +
            "                # The /login.jsp is not restricted to authenticated users (otherwise no one could log in!), but\r\n" +
            "                # the 'authc' filter must still be specified for it so it can process that url's\r\n" +
            "                # login submissions. It is 'smart' enough to allow those requests through as specified by the\r\n" +
            "                # shiro.loginUrl above.\r\n" +
            "                /login.jsp = authc\r\n" +
            "\r\n" +
            "                /oauth/authorize/** = authc\r\n" +
            "                /oauth2/authorize/** = authc\r\n" +
            "";

    private static final String RE_GUICE_MODULES = "" +
            "    org.overlord.gadgets.web.server.GadgetServerModule,\r\n" +
            "    org.overlord.gadgets.server.CoreModule\r\n";

    private static final String DB_SEED_DATA =
            "INSERT INTO GS_GROUP(`GROUP_ID`,`GROUP_NAME`, `GROUP_DESC`) VALUES(1, 'system', 'reserved system group');\r\n" +
            "INSERT INTO GS_GROUP(`GROUP_ID`,`GROUP_NAME`, `GROUP_DESC`) VALUES(2, 'users', 'all users');\r\n" +
            "INSERT INTO GS_USER(`ID`, `NAME`, `DISPLAY_NAME`, `PASSWD`, `USER_ROLE`) VALUES(1, 'admin', 'Administrator', 'admin','ADMIN');\r\n" +
            "INSERT INTO GS_USER(`ID`, `NAME`, `DISPLAY_NAME`, `PASSWD`, `USER_ROLE`) VALUES(2, 'eric', 'Eric', 'eric','USER');\r\n" +
            "INSERT INTO GS_USER(`ID`, `NAME`, `DISPLAY_NAME`, `PASSWD`, `USER_ROLE`) VALUES(3, 'gary', 'Gary', 'gary','USER');\r\n" +
            "INSERT INTO GS_USER_GROUP(`USER_ID`, `GROUP_ID`) VALUES(1, 1);\r\n" +
            "INSERT INTO GS_USER_GROUP(`USER_ID`, `GROUP_ID`) VALUES(2, 2);\r\n" +
            "INSERT INTO GS_USER_GROUP(`USER_ID`, `GROUP_ID`) VALUES(3, 2);\r\n" +
            "\r\n" +
            "\r\n" +
            "INSERT INTO GS_GADGET(`GADGET_TITLE`,`GADGET_AUTHOR`,`GADGET_AUTHOR_EMAIL`,`GADGET_DESCRIPTION`,`GADGET_THUMBNAIL_URL`,`GADGET_URL`) VALUES('Date & Time','Google','admin@google.com','Add a clock to your page. Click edit to change it to the color of your choice','http://gadgets.adwebmaster.net/images/gadgets/datetimemulti/thumbnail_en.jpg','http://www.gstatic.com/ig/modules/datetime_v3/datetime_v3.xml');\r\n" +
            "INSERT INTO GS_GADGET(`GADGET_TITLE`,`GADGET_AUTHOR`,`GADGET_AUTHOR_EMAIL`,`GADGET_DESCRIPTION`,`GADGET_THUMBNAIL_URL`,`GADGET_URL`) VALUES('Response Time','Jeff Yu','jeffyu@overlord.com','Response Time Gadget','http://localhost:8080/gadgets/rt-gadget/thumbnail.png','http://localhost:8080/gadgets/rt-gadget/gadget.xml');\r\n" +
            "INSERT INTO GS_GADGET(`GADGET_TITLE`,`GADGET_AUTHOR`,`GADGET_AUTHOR_EMAIL`,`GADGET_DESCRIPTION`,`GADGET_THUMBNAIL_URL`,`GADGET_URL`) VALUES('Currency Converter','Google','info@tofollow.com','currency converter widget','http://www.gstatic.com/ig/modules/currency_converter/currency_converter_content/en_us-thm.cache.png','http://www.gstatic.com/ig/modules/currency_converter/currency_converter_v2.xml');\r\n" +
            "INSERT INTO GS_GADGET(`GADGET_TITLE`,`GADGET_AUTHOR`,`GADGET_AUTHOR_EMAIL`,`GADGET_DESCRIPTION`,`GADGET_THUMBNAIL_URL`,`GADGET_URL`) VALUES('SLA Gadget','Jeff Yu','jeffyu@overlord.com','Service Level Violation Gadget','http://localhost:8080/gadgets/sla-gadget/thumbnail.png','http://localhost:8080/gadgets/sla-gadget/gadget.xml');\r\n" +
            "INSERT INTO GS_GADGET(`GADGET_TITLE`,`GADGET_AUTHOR`,`GADGET_AUTHOR_EMAIL`,`GADGET_DESCRIPTION`,`GADGET_THUMBNAIL_URL`,`GADGET_URL`) VALUES('Economic Data - ALFRED Graph','Research Department','webmaster@research.stlouisfed.org','Vintage Economic Data from the Federal Reserve Bank of St. Louis','http://research.stlouisfed.org/gadgets/images/alfredgraphgadgetthumbnail.png','http://research.stlouisfed.org/gadgets/code/alfredgraph.xml');";

    private static final String [] USERS = { "admin", "eric", "gary" };

}
