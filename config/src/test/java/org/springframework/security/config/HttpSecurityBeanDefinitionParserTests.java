package org.springframework.security.config;

import static org.junit.Assert.*;
import static org.springframework.security.config.ConfigTestUtils.AUTH_PROVIDER_XML;
import static org.springframework.security.config.HttpSecurityBeanDefinitionParser.*;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

import javax.servlet.Filter;

import org.junit.After;
import org.junit.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.parsing.BeanDefinitionParsingException;
import org.springframework.context.support.AbstractXmlApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.MockAuthenticationEntryPoint;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.access.SecurityConfig;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.concurrent.ConcurrentLoginException;
import org.springframework.security.authentication.concurrent.ConcurrentSessionControllerImpl;
import org.springframework.security.authentication.concurrent.SessionRegistryImpl;
import org.springframework.security.config.util.InMemoryXmlApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.openid.OpenIDAuthenticationProcessingFilter;
import org.springframework.security.openid.OpenIDAuthenticationProvider;
import org.springframework.security.util.FieldUtils;
import org.springframework.security.web.ExceptionTranslationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.FilterInvocation;
import org.springframework.security.web.PortMapperImpl;
import org.springframework.security.web.SessionFixationProtectionFilter;
import org.springframework.security.web.authentication.AnonymousProcessingFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationProcessingFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.DefaultLoginPageGeneratingFilter;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.preauth.x509.X509PreAuthenticatedProcessingFilter;
import org.springframework.security.web.authentication.rememberme.InMemoryTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.authentication.www.BasicProcessingFilter;
import org.springframework.security.web.concurrent.ConcurrentSessionFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextPersistenceFilter;
import org.springframework.security.web.expression.DefaultWebSecurityExpressionHandler;
import org.springframework.security.web.expression.ExpressionBasedFilterInvocationSecurityMetadataSource;
import org.springframework.security.web.intercept.FilterInvocationSecurityMetadataSource;
import org.springframework.security.web.intercept.FilterSecurityInterceptor;
import org.springframework.security.web.logout.LogoutFilter;
import org.springframework.security.web.logout.LogoutHandler;
import org.springframework.security.web.securechannel.ChannelProcessingFilter;
import org.springframework.security.web.wrapper.SecurityContextHolderAwareRequestFilter;
import org.springframework.util.ReflectionUtils;

/**
 * @author Luke Taylor
 * @version $Id$
 */
public class HttpSecurityBeanDefinitionParserTests {
    private static final int AUTO_CONFIG_FILTERS = 10;
    private AbstractXmlApplicationContext appContext;

    @After
    public void closeAppContext() {
        if (appContext != null) {
            appContext.close();
            appContext = null;
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    public void minimalConfigurationParses() {
        setContext("<http><http-basic /></http>" + AUTH_PROVIDER_XML);
    }

    @Test
    public void beanClassNamesAreCorrect() throws Exception {
        assertEquals(DefaultWebSecurityExpressionHandler.class.getName(), EXPRESSION_HANDLER_CLASS);
        assertEquals(ExpressionBasedFilterInvocationSecurityMetadataSource.class.getName(), EXPRESSION_FIMDS_CLASS);
        assertEquals(AuthenticationProcessingFilter.class.getName(), AUTHENTICATION_PROCESSING_FILTER_CLASS);
        assertEquals(OpenIDAuthenticationProcessingFilter.class.getName(), OPEN_ID_AUTHENTICATION_PROCESSING_FILTER_CLASS);
        assertEquals(OpenIDAuthenticationProvider.class.getName(), OPEN_ID_AUTHENTICATION_PROVIDER_CLASS);
    }

    @Test
    public void httpAutoConfigSetsUpCorrectFilterList() throws Exception {
        setContext("<http auto-config='true' />" + AUTH_PROVIDER_XML);

        List<Filter> filterList = getFilters("/anyurl");

        checkAutoConfigFilters(filterList);

        assertEquals(true, FieldUtils.getFieldValue(appContext.getBean("_filterChainProxy"), "stripQueryStringFromUrls"));
        assertEquals(true, FieldUtils.getFieldValue(filterList.get(AUTO_CONFIG_FILTERS-1), "securityMetadataSource.stripQueryStringFromUrls"));
    }

    @Test(expected=BeanDefinitionParsingException.class)
    public void duplicateElementCausesError() throws Exception {
        setContext("<http auto-config='true' /><http auto-config='true' />" + AUTH_PROVIDER_XML);
    }

    private void checkAutoConfigFilters(List<Filter> filterList) throws Exception {
        assertEquals("Expected " + AUTO_CONFIG_FILTERS + " filters in chain", AUTO_CONFIG_FILTERS, filterList.size());

        Iterator<Filter> filters = filterList.iterator();

        assertTrue(filters.next() instanceof SecurityContextPersistenceFilter);
        assertTrue(filters.next() instanceof LogoutFilter);
        Object authProcFilter = filters.next();
        assertTrue(authProcFilter instanceof AuthenticationProcessingFilter);
        // Check RememberMeServices has been set on AuthenticationProcessingFilter
        //Object rms = FieldUtils.getFieldValue(authProcFilter, "rememberMeServices");
        //assertNotNull(rms);
        //assertTrue(rms instanceof RememberMeServices);
        //assertFalse(rms instanceof NullRememberMeServices);
        assertTrue(filters.next() instanceof DefaultLoginPageGeneratingFilter);
        assertTrue(filters.next() instanceof BasicProcessingFilter);
        assertTrue(filters.next() instanceof SecurityContextHolderAwareRequestFilter);
        //assertTrue(filters.next() instanceof RememberMeProcessingFilter);
        assertTrue(filters.next() instanceof AnonymousProcessingFilter);
        assertTrue(filters.next() instanceof ExceptionTranslationFilter);
        assertTrue(filters.next() instanceof SessionFixationProtectionFilter);
        Object fsiObj = filters.next();
        assertTrue(fsiObj instanceof FilterSecurityInterceptor);
        FilterSecurityInterceptor fsi = (FilterSecurityInterceptor) fsiObj;
        assertTrue(fsi.isObserveOncePerRequest());
    }

    @Test
    public void filterListShouldBeEmptyForUnprotectedUrl() throws Exception {
        setContext(
                "    <http auto-config='true'>" +
                "        <intercept-url pattern='/unprotected' filters='none' />" +
                "    </http>" + AUTH_PROVIDER_XML);

        List<Filter> filters = getFilters("/unprotected");

        assertTrue(filters.size() == 0);
    }

    @Test
    public void regexPathsWorkCorrectly() throws Exception {
        setContext(
                "    <http auto-config='true' path-type='regex'>" +
                "        <intercept-url pattern='\\A\\/[a-z]+' filters='none' />" +
                "    </http>" + AUTH_PROVIDER_XML);
        assertEquals(0, getFilters("/imlowercase").size());
        // This will be matched by the default pattern ".*"
        List<Filter> allFilters = getFilters("/ImCaughtByTheUniversalMatchPattern");
        checkAutoConfigFilters(allFilters);
        assertEquals(false, FieldUtils.getFieldValue(appContext.getBean(BeanIds.FILTER_CHAIN_PROXY), "stripQueryStringFromUrls"));
        assertEquals(false, FieldUtils.getFieldValue(allFilters.get(AUTO_CONFIG_FILTERS-1), "securityMetadataSource.stripQueryStringFromUrls"));
    }

    @Test
    public void lowerCaseComparisonAttributeIsRespectedByFilterChainProxy() throws Exception {
        setContext(
                "    <http auto-config='true' path-type='ant' lowercase-comparisons='false'>" +
                "        <intercept-url pattern='/Secure*' filters='none' />" +
                "    </http>" + AUTH_PROVIDER_XML);
        assertEquals(0, getFilters("/Secure").size());
        // These will be matched by the default pattern "/**"
        checkAutoConfigFilters(getFilters("/secure"));
        checkAutoConfigFilters(getFilters("/ImCaughtByTheUniversalMatchPattern"));
    }

    @Test
    public void formLoginWithNoLoginPageAddsDefaultLoginPageFilter() throws Exception {
        setContext(
                "<http auto-config='true' path-type='ant' lowercase-comparisons='false'>" +
                "   <form-login />" +
                "</http>" + AUTH_PROVIDER_XML);
        // These will be matched by the default pattern "/**"
        checkAutoConfigFilters(getFilters("/anything"));
    }

    @Test
    public void formLoginAlwaysUseDefaultSetsCorrectProperty() throws Exception {
        setContext(
                "<http>" +
                "   <form-login default-target-url='/default' always-use-default-target='true' />" +
                "</http>" + AUTH_PROVIDER_XML);
        // These will be matched by the default pattern "/**"
        AuthenticationProcessingFilter filter = (AuthenticationProcessingFilter) getFilters("/anything").get(1);
        assertEquals("/default", FieldUtils.getFieldValue(filter, "successHandler.defaultTargetUrl"));
        assertEquals(Boolean.TRUE, FieldUtils.getFieldValue(filter, "successHandler.alwaysUseDefaultTargetUrl"));
    }

    @Test(expected=BeanCreationException.class)
    public void invalidLoginPageIsDetected() throws Exception {
        setContext(
                "<http>" +
                "   <form-login login-page='noLeadingSlash'/>" +
                "</http>" + AUTH_PROVIDER_XML);
    }

    @Test(expected=BeanCreationException.class)
    public void invalidDefaultTargetUrlIsDetected() throws Exception {
        setContext(
                "<http>" +
                "   <form-login default-target-url='noLeadingSlash'/>" +
                "</http>" + AUTH_PROVIDER_XML);
    }

    @Test(expected=BeanCreationException.class)
    public void invalidLogoutUrlIsDetected() throws Exception {
        setContext(
                "<http>" +
                "   <logout logout-url='noLeadingSlash'/>" +
                "   <form-login />" +
                "</http>" + AUTH_PROVIDER_XML);
    }

    @Test(expected=BeanCreationException.class)
    public void invalidLogoutSuccessUrlIsDetected() throws Exception {
        setContext(
                "<http>" +
                "   <logout logout-success-url='noLeadingSlash'/>" +
                "   <form-login />" +
                "</http>" + AUTH_PROVIDER_XML);
    }

    @Test
    public void lowerCaseComparisonIsRespectedBySecurityFilterInvocationDefinitionSource() throws Exception {
        setContext(
                "    <http auto-config='true' path-type='ant' lowercase-comparisons='false'>" +
                "        <intercept-url pattern='/Secure*' access='ROLE_A,ROLE_B' />" +
                "        <intercept-url pattern='/**' access='ROLE_C' />" +
                "    </http>" + AUTH_PROVIDER_XML);

        FilterSecurityInterceptor fis = (FilterSecurityInterceptor) appContext.getBean(BeanIds.FILTER_SECURITY_INTERCEPTOR);

        FilterInvocationSecurityMetadataSource fids = fis.getSecurityMetadataSource();
        List<? extends ConfigAttribute> attrDef = fids.getAttributes(createFilterinvocation("/Secure", null));
        assertEquals(2, attrDef.size());
        assertTrue(attrDef.contains(new SecurityConfig("ROLE_A")));
        assertTrue(attrDef.contains(new SecurityConfig("ROLE_B")));
        attrDef = fids.getAttributes(createFilterinvocation("/secure", null));
        assertEquals(1, attrDef.size());
        assertTrue(attrDef.contains(new SecurityConfig("ROLE_C")));
    }

    @Test
    public void httpMethodMatchIsSupported() throws Exception {
        setContext(
                "    <http auto-config='true'>" +
                "        <intercept-url pattern='/**' access='ROLE_C' />" +
                "        <intercept-url pattern='/secure*' method='DELETE' access='ROLE_SUPERVISOR' />" +
                "        <intercept-url pattern='/secure*' method='POST' access='ROLE_A,ROLE_B' />" +
                "    </http>" + AUTH_PROVIDER_XML);

        FilterSecurityInterceptor fis = (FilterSecurityInterceptor) appContext.getBean(BeanIds.FILTER_SECURITY_INTERCEPTOR);
        FilterInvocationSecurityMetadataSource fids = fis.getSecurityMetadataSource();
        List<? extends ConfigAttribute> attrs = fids.getAttributes(createFilterinvocation("/secure", "POST"));
        assertEquals(2, attrs.size());
        assertTrue(attrs.contains(new SecurityConfig("ROLE_A")));
        assertTrue(attrs.contains(new SecurityConfig("ROLE_B")));
    }

    @Test
    public void oncePerRequestAttributeIsSupported() throws Exception {
        setContext("<http once-per-request='false'><http-basic /></http>" + AUTH_PROVIDER_XML);
        List<Filter> filters = getFilters("/someurl");

        FilterSecurityInterceptor fsi = (FilterSecurityInterceptor) filters.get(filters.size() - 1);

        assertFalse(fsi.isObserveOncePerRequest());
    }

    @Test
    public void accessDeniedPageAttributeIsSupported() throws Exception {
        setContext("<http access-denied-page='/access-denied'><http-basic /></http>" + AUTH_PROVIDER_XML);
        List<Filter> filters = getFilters("/someurl");

        ExceptionTranslationFilter etf = (ExceptionTranslationFilter) filters.get(filters.size() - 3);

        assertEquals("/access-denied", FieldUtils.getFieldValue(etf, "accessDeniedHandler.errorPage"));
    }

    @Test(expected=BeanCreationException.class)
    public void invalidAccessDeniedUrlIsDetected() throws Exception {
        setContext("<http auto-config='true' access-denied-page='noLeadingSlash'/>" + AUTH_PROVIDER_XML);
    }

    @Test
    public void interceptUrlWithRequiresChannelAddsChannelFilterToStack() throws Exception {
        setContext(
                "    <http auto-config='true'>" +
                "        <intercept-url pattern='/**' requires-channel='https' />" +
                "    </http>" + AUTH_PROVIDER_XML);
        List<Filter> filters = getFilters("/someurl");

        assertEquals("Expected " + (AUTO_CONFIG_FILTERS + 1) +"  filters in chain", AUTO_CONFIG_FILTERS + 1, filters.size());

        assertTrue(filters.get(0) instanceof ChannelProcessingFilter);
    }

    @Test
    public void portMappingsAreParsedCorrectly() throws Exception {
        setContext(
                "    <http auto-config='true'>" +
                "        <port-mappings>" +
                "            <port-mapping http='9080' https='9443'/>" +
                "        </port-mappings>" +
                "    </http>" + AUTH_PROVIDER_XML);

        PortMapperImpl pm = (PortMapperImpl) appContext.getBean(BeanIds.PORT_MAPPER);
        assertEquals(1, pm.getTranslatedPortMappings().size());
        assertEquals(Integer.valueOf(9080), pm.lookupHttpPort(9443));
        assertEquals(Integer.valueOf(9443), pm.lookupHttpsPort(9080));
    }

    @Test
    public void portMappingsWorkWithPlaceholders() throws Exception {
        System.setProperty("http", "9080");
        System.setProperty("https", "9443");
        setContext(
                "    <b:bean id='configurer' class='org.springframework.beans.factory.config.PropertyPlaceholderConfigurer'/>" +
                "    <http auto-config='true'>" +
                "        <port-mappings>" +
                "            <port-mapping http='${http}' https='${https}'/>" +
                "        </port-mappings>" +
                "    </http>" + AUTH_PROVIDER_XML);

        PortMapperImpl pm = (PortMapperImpl) appContext.getBean(BeanIds.PORT_MAPPER);
        assertEquals(1, pm.getTranslatedPortMappings().size());
        assertEquals(Integer.valueOf(9080), pm.lookupHttpPort(9443));
        assertEquals(Integer.valueOf(9443), pm.lookupHttpsPort(9080));
    }

    @Test
    public void accessDeniedPageWorkWithPlaceholders() throws Exception {
        System.setProperty("accessDenied", "/go-away");
        setContext(
                "    <b:bean id='configurer' class='org.springframework.beans.factory.config.PropertyPlaceholderConfigurer'/>" +
                "    <http auto-config='true' access-denied-page='${accessDenied}'/>" + AUTH_PROVIDER_XML);
        ExceptionTranslationFilter filter = (ExceptionTranslationFilter) appContext.getBean(BeanIds.EXCEPTION_TRANSLATION_FILTER);
        assertEquals("/go-away", FieldUtils.getFieldValue(filter, "accessDeniedHandler.errorPage"));
    }

    @Test
    public void externalFiltersAreTreatedCorrectly() throws Exception {
        // Decorated user-filters should be added to stack. The others should be ignored.
        String contextHolderFilterClass = SecurityContextHolderAwareRequestFilter.class.getName();
        String contextPersistenceFilterClass = SecurityContextPersistenceFilter.class.getName();

        setContext(
                "<http auto-config='true'/>" + AUTH_PROVIDER_XML +
                "<b:bean id='userFilter' class='"+ contextHolderFilterClass +"'>" +
                "    <custom-filter after='LOGOUT_FILTER'/>" +
                "</b:bean>" +
                "<b:bean id='userFilter1' class='" + contextPersistenceFilterClass + "'>" +
                "    <custom-filter before='SESSION_CONTEXT_INTEGRATION_FILTER'/>" +
                "</b:bean>" +
                "<b:bean id='userFilter2' class='" + contextPersistenceFilterClass + "'>" +
                "    <custom-filter position='FIRST'/>" +
                "</b:bean>" +
                "<b:bean id='userFilter3' class='" + contextPersistenceFilterClass + "'/>" +
                "<b:bean id='userFilter4' class='"+ contextHolderFilterClass +"'/>"
                );
        List<Filter> filters = getFilters("/someurl");

        assertEquals(AUTO_CONFIG_FILTERS + 3, filters.size());
        assertTrue(filters.get(0) instanceof SecurityContextPersistenceFilter);
        assertTrue(filters.get(1) instanceof SecurityContextPersistenceFilter);
        assertTrue(filters.get(4) instanceof SecurityContextHolderAwareRequestFilter);
    }

    @Test(expected=BeanCreationException.class)
    public void twoFiltersWithSameOrderAreRejected() {
        setContext(
                "<http auto-config='true'/>" + AUTH_PROVIDER_XML +
                "<b:bean id='userFilter' class='" + SecurityContextHolderAwareRequestFilter.class.getName() + "'>" +
                "    <custom-filter position='LOGOUT_FILTER'/>" +
                "</b:bean>");
    }

    @Test
    public void rememberMeServiceWorksWithTokenRepoRef() {
        setContext(
            "<http auto-config='true'>" +
            "    <remember-me token-repository-ref='tokenRepo'/>" +
            "</http>" +
            "<b:bean id='tokenRepo' " +
                    "class='" + InMemoryTokenRepositoryImpl.class.getName() + "'/> " + AUTH_PROVIDER_XML);
        Object rememberMeServices = appContext.getBean(BeanIds.REMEMBER_ME_SERVICES);

        assertTrue(rememberMeServices instanceof PersistentTokenBasedRememberMeServices);
    }

    @Test
    public void rememberMeServiceWorksWithDataSourceRef() {
        setContext(
                "<http auto-config='true'>" +
                "    <remember-me data-source-ref='ds'/>" +
                "</http>" +
                "<b:bean id='ds' class='org.springframework.security.TestDataSource'> " +
                "    <b:constructor-arg value='tokendb'/>" +
                "</b:bean>" + AUTH_PROVIDER_XML);
        Object rememberMeServices = appContext.getBean(BeanIds.REMEMBER_ME_SERVICES);

        assertTrue(rememberMeServices instanceof PersistentTokenBasedRememberMeServices);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void rememberMeServiceWorksWithExternalServicesImpl() throws Exception {
        setContext(
                "<http auto-config='true'>" +
                "    <remember-me key='ourkey' services-ref='rms'/>" +
                "</http>" +
                "<b:bean id='rms' class='"+ TokenBasedRememberMeServices.class.getName() +"'> " +
                "    <b:property name='userDetailsService' ref='us'/>" +
                "    <b:property name='key' value='ourkey'/>" +
                "    <b:property name='tokenValiditySeconds' value='5000'/>" +
                "</b:bean>" +
                AUTH_PROVIDER_XML);

        assertEquals(5000, FieldUtils.getFieldValue(appContext.getBean(BeanIds.REMEMBER_ME_SERVICES),
                "tokenValiditySeconds"));
        // SEC-909
        List<LogoutHandler> logoutHandlers = (List<LogoutHandler>) FieldUtils.getFieldValue(appContext.getBean(BeanIds.LOGOUT_FILTER), "handlers");
        assertEquals(2, logoutHandlers.size());
        assertEquals(appContext.getBean(BeanIds.REMEMBER_ME_SERVICES), logoutHandlers.get(1));
    }

    @Test
    public void rememberMeTokenValidityIsParsedCorrectly() throws Exception {
        setContext(
                "<http auto-config='true'>" +
                "    <remember-me key='ourkey' token-validity-seconds='10000' />" +
                "</http>" + AUTH_PROVIDER_XML);
        assertEquals(10000, FieldUtils.getFieldValue(appContext.getBean(BeanIds.REMEMBER_ME_SERVICES),
                "tokenValiditySeconds"));
    }

    @Test
    public void rememberMeTokenValidityAllowsNegativeValueForNonPersistentImplementation() throws Exception {
        setContext(
                "<http auto-config='true'>" +
                "    <remember-me key='ourkey' token-validity-seconds='-1' />" +
                "</http>" + AUTH_PROVIDER_XML);
        assertEquals(-1, FieldUtils.getFieldValue(appContext.getBean(BeanIds.REMEMBER_ME_SERVICES),
                "tokenValiditySeconds"));
    }

    @Test(expected=BeanDefinitionParsingException.class)
    public void rememberMeTokenValidityRejectsNegativeValueForPersistentImplementation() throws Exception {
        setContext(
            "<http auto-config='true'>" +
            "    <remember-me token-validity-seconds='-1' token-repository-ref='tokenRepo'/>" +
            "</http>" +
            "<b:bean id='tokenRepo' class='org.springframework.security.ui.rememberme.InMemoryTokenRepositoryImpl'/> " +
                    AUTH_PROVIDER_XML);
    }

    @Test
    public void rememberMeServiceConfigurationParsesWithCustomUserService() {
        setContext(
                "<http auto-config='true'>" +
                "    <remember-me key='somekey' user-service-ref='userService'/>" +
                "</http>" +
                "<b:bean id='userService' class='org.springframework.security.core.userdetails.MockUserDetailsService'/> " +
                AUTH_PROVIDER_XML);
//        AbstractRememberMeServices rememberMeServices = (AbstractRememberMeServices) appContext.getBean(BeanIds.REMEMBER_ME_SERVICES);
    }

    @Test
    public void x509SupportAddsFilterAtExpectedPosition() throws Exception {
        setContext(
                "<http auto-config='true'>" +
                "    <x509 />" +
                "</http>"  + AUTH_PROVIDER_XML);
        List<Filter> filters = getFilters("/someurl");

        assertTrue(filters.get(2) instanceof X509PreAuthenticatedProcessingFilter);
    }

    @Test
    public void concurrentSessionSupportAddsFilterAndExpectedBeans() throws Exception {
        setContext(
                "<http auto-config='true'>" +
                "    <concurrent-session-control session-registry-alias='seshRegistry' expired-url='/expired'/>" +
                "</http>" + AUTH_PROVIDER_XML);
        List<Filter> filters = getFilters("/someurl");

        assertTrue(filters.get(0) instanceof ConcurrentSessionFilter);
        assertNotNull(appContext.getBean("seshRegistry"));
        assertNotNull(appContext.getBean(BeanIds.CONCURRENT_SESSION_CONTROLLER));
    }

    @Test
    public void externalSessionRegistryBeanIsConfiguredCorrectly() throws Exception {
        setContext(
                "<http auto-config='true'>" +
                "    <concurrent-session-control session-registry-ref='seshRegistry' />" +
                "</http>" +
                "<b:bean id='seshRegistry' class='" + SessionRegistryImpl.class.getName() + "'/>" +
                AUTH_PROVIDER_XML);
        Object sessionRegistry = appContext.getBean("seshRegistry");
        Object sessionRegistryFromFilter = FieldUtils.getFieldValue(
                appContext.getBean(BeanIds.CONCURRENT_SESSION_FILTER),"sessionRegistry");
        Object sessionRegistryFromController = FieldUtils.getFieldValue(
                appContext.getBean(BeanIds.CONCURRENT_SESSION_CONTROLLER),"sessionRegistry");
        Object sessionRegistryFromFixationFilter = FieldUtils.getFieldValue(
                appContext.getBean(BeanIds.SESSION_FIXATION_PROTECTION_FILTER),"sessionRegistry");

        assertSame(sessionRegistry, sessionRegistryFromFilter);
        assertSame(sessionRegistry, sessionRegistryFromController);
        assertSame(sessionRegistry, sessionRegistryFromFixationFilter);
    }

    @Test(expected=BeanDefinitionParsingException.class)
    public void concurrentSessionSupportCantBeUsedWithIndependentControllerBean() throws Exception {
        setContext(
                "<authentication-manager alias='authManager' session-controller-ref='sc'/>" +
                "<http auto-config='true'>" +
                "    <concurrent-session-control session-registry-alias='seshRegistry' expired-url='/expired'/>" +
                "</http>" +
                "<b:bean id='sc' class='" + ConcurrentSessionControllerImpl.class.getName() +"'>" +
                "  <b:property name='sessionRegistry'>" +
                "    <b:bean class='"+ SessionRegistryImpl.class.getName() + "'/>" +
                "  </b:property>" +
                "</b:bean>" + AUTH_PROVIDER_XML);
    }

    @Test(expected=BeanDefinitionParsingException.class)
    public void concurrentSessionSupportCantBeUsedWithIndependentControllerBean2() throws Exception {
        setContext(
                "<http auto-config='true'>" +
                "    <concurrent-session-control session-registry-alias='seshRegistry' expired-url='/expired'/>" +
                "</http>" +
                "<b:bean id='sc' class='org.springframework.security.authentication.concurrent.ConcurrentSessionControllerImpl'>" +
                "  <b:property name='sessionRegistry'>" +
                "    <b:bean class='" + SessionRegistryImpl.class.getName() + "'/>" +
                "  </b:property>" +
                "</b:bean>" +
                "<authentication-manager alias='authManager' session-controller-ref='sc'/>" + AUTH_PROVIDER_XML);
    }

    @Test(expected=ConcurrentLoginException.class)
    public void concurrentSessionMaxSessionsIsCorrectlyConfigured() throws Exception {
        setContext(
                "<http auto-config='true'>" +
                "    <concurrent-session-control max-sessions='2' exception-if-maximum-exceeded='true' />" +
                "</http>"  + AUTH_PROVIDER_XML);
        ConcurrentSessionControllerImpl seshController = (ConcurrentSessionControllerImpl) appContext.getBean(BeanIds.CONCURRENT_SESSION_CONTROLLER);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("bob", "pass");
        // Register 2 sessions and then check a third
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setSession(new MockHttpSession());
        auth.setDetails(new WebAuthenticationDetails(req));
        try {
            seshController.checkAuthenticationAllowed(auth);
        } catch (ConcurrentLoginException e) {
            fail("First login should be allowed");
        }
        seshController.registerSuccessfulAuthentication(auth);
        req.setSession(new MockHttpSession());
        try {
            seshController.checkAuthenticationAllowed(auth);
        } catch (ConcurrentLoginException e) {
            fail("Second login should be allowed");
        }
        auth.setDetails(new WebAuthenticationDetails(req));
        seshController.registerSuccessfulAuthentication(auth);
        req.setSession(new MockHttpSession());
        auth.setDetails(new WebAuthenticationDetails(req));
        seshController.checkAuthenticationAllowed(auth);
    }

    @Test
    public void customEntryPointIsSupported() throws Exception {
        setContext(
                "<http auto-config='true' entry-point-ref='entryPoint'/>" +
                "<b:bean id='entryPoint' class='" + MockAuthenticationEntryPoint.class.getName() + "'>" +
                "    <b:constructor-arg value='/customlogin'/>" +
                "</b:bean>" + AUTH_PROVIDER_XML);
        ExceptionTranslationFilter etf = (ExceptionTranslationFilter) getFilters("/someurl").get(AUTO_CONFIG_FILTERS-3);
        assertTrue("ExceptionTranslationFilter should be configured with custom entry point",
                etf.getAuthenticationEntryPoint() instanceof MockAuthenticationEntryPoint);
    }

    @Test
    /** SEC-742 */
    public void rememberMeServicesWorksWithoutBasicProcessingFilter() {
        setContext(
                "    <http>" +
                "        <form-login login-page='/login.jsp' default-target-url='/messageList.html'/>" +
                "        <logout logout-success-url='/login.jsp'/>" +
                "        <anonymous username='guest' granted-authority='guest'/>" +
                "        <remember-me />" +
                "    </http>" + AUTH_PROVIDER_XML);
    }

    @Test
    public void disablingSessionProtectionRemovesFilter() throws Exception {
        setContext(
                "<http auto-config='true' session-fixation-protection='none'/>" + AUTH_PROVIDER_XML);
        List<Filter> filters = getFilters("/someurl");

        assertFalse(filters.get(1) instanceof SessionFixationProtectionFilter);
    }

    /**
     * See SEC-750. If the http security post processor causes beans to be instantiated too eagerly, they way miss
     * additional processing. In this method we have a UserDetailsService which is referenced from the namespace
     * and also has a post processor registered which will modify it.
     */
    @Test
    public void httpElementDoesntInterfereWithBeanPostProcessing() {
        setContext(
                "<http auto-config='true'/>" +
                "<authentication-provider user-service-ref='myUserService'/>" +
                "<b:bean id='myUserService' class='org.springframework.security.config.PostProcessedMockUserDetailsService'/>" +
                "<b:bean id='beanPostProcessor' class='org.springframework.security.config.MockUserServiceBeanPostProcessor'/>"
        );

        PostProcessedMockUserDetailsService service = (PostProcessedMockUserDetailsService)appContext.getBean("myUserService");

        assertEquals("Hello from the post processor!", service.getPostProcessorWasHere());
    }

    /**
     * SEC-795. Two methods that exercise the scenarios that will or won't result in a protected login page warning.
     * Check the log.
     */
    @Test
    public void unprotectedLoginPageDoesntResultInWarning() {
        // Anonymous access configured
        setContext(
                "    <http>" +
                "        <intercept-url pattern='/login.jsp*' access='IS_AUTHENTICATED_ANONYMOUSLY'/>" +
                "        <intercept-url pattern='/**' access='ROLE_A'/>" +
                "        <anonymous />" +
                "        <form-login login-page='/login.jsp' default-target-url='/messageList.html'/>" +
                "    </http>" + AUTH_PROVIDER_XML);
        closeAppContext();
        // No filters applied to login page
        setContext(
                "    <http>" +
                "        <intercept-url pattern='/login.jsp*' filters='none'/>" +
                "        <intercept-url pattern='/**' access='ROLE_A'/>" +
                "        <anonymous />" +
                "        <form-login login-page='/login.jsp' default-target-url='/messageList.html'/>" +
                "    </http>" + AUTH_PROVIDER_XML);
    }

    @Test
    public void protectedLoginPageResultsInWarning() {
        // Protected, no anonymous filter configured.
        setContext(
                "    <http>" +
                "        <intercept-url pattern='/**' access='ROLE_A'/>" +
                "        <form-login login-page='/login.jsp' default-target-url='/messageList.html'/>" +
                "    </http>" + AUTH_PROVIDER_XML);
        closeAppContext();
        // Protected, anonymous provider but no access
        setContext(
                "    <http>" +
                "        <intercept-url pattern='/**' access='ROLE_A'/>" +
                "        <anonymous />" +
                "        <form-login login-page='/login.jsp' default-target-url='/messageList.html'/>" +
                "    </http>" + AUTH_PROVIDER_XML);
    }

    @Test
    public void settingCreateSessionToAlwaysSetsFilterPropertiesCorrectly() throws Exception {
        setContext("<http auto-config='true' create-session='always'/>" + AUTH_PROVIDER_XML);
        Object filter = appContext.getBean(BeanIds.SECURITY_CONTEXT_PERSISTENCE_FILTER);

        assertEquals(Boolean.TRUE, FieldUtils.getFieldValue(filter, "forceEagerSessionCreation"));
        assertEquals(Boolean.TRUE, FieldUtils.getFieldValue(filter, "repo.allowSessionCreation"));
        // Just check that the repo has url rewriting enabled by default
        assertEquals(Boolean.FALSE, FieldUtils.getFieldValue(filter, "repo.disableUrlRewriting"));
    }

    @Test
    public void settingCreateSessionToNeverSetsFilterPropertiesCorrectly() throws Exception {
        setContext("<http auto-config='true' create-session='never'/>" + AUTH_PROVIDER_XML);
        Object filter = appContext.getBean(BeanIds.SECURITY_CONTEXT_PERSISTENCE_FILTER);
        assertEquals(Boolean.FALSE, FieldUtils.getFieldValue(filter, "forceEagerSessionCreation"));
        assertEquals(Boolean.FALSE, FieldUtils.getFieldValue(filter, "repo.allowSessionCreation"));
    }

    /* SEC-934 */
    @Test
    public void supportsTwoIdenticalInterceptUrls() {
        setContext(
                "<http auto-config='true'>" +
                "    <intercept-url pattern='/someurl' access='ROLE_A'/>" +
                "    <intercept-url pattern='/someurl' access='ROLE_B'/>" +
                "</http>" + AUTH_PROVIDER_XML);
        FilterSecurityInterceptor fis = (FilterSecurityInterceptor) appContext.getBean(BeanIds.FILTER_SECURITY_INTERCEPTOR);

        FilterInvocationSecurityMetadataSource fids = fis.getSecurityMetadataSource();
        List<? extends ConfigAttribute> attrDef = fids.getAttributes(createFilterinvocation("/someurl", null));
        assertEquals(1, attrDef.size());
        assertTrue(attrDef.contains(new SecurityConfig("ROLE_B")));
    }

    @Test
    public void supportsExternallyDefinedSecurityContextRepository() throws Exception {
        setContext(
                "<b:bean id='repo' class='" + HttpSessionSecurityContextRepository.class.getName() + "'/>" +
                "<http create-session='always' security-context-repository-ref='repo'>" +
                "    <http-basic />" +
                "</http>" + AUTH_PROVIDER_XML);
        SecurityContextPersistenceFilter filter = (SecurityContextPersistenceFilter) appContext.getBean(BeanIds.SECURITY_CONTEXT_PERSISTENCE_FILTER);
        HttpSessionSecurityContextRepository repo = (HttpSessionSecurityContextRepository) appContext.getBean("repo");
        assertSame(repo, FieldUtils.getFieldValue(filter, "repo"));
        assertTrue((Boolean)FieldUtils.getFieldValue(filter, "forceEagerSessionCreation"));
    }

    @Test(expected=BeanDefinitionParsingException.class)
    public void cantUseUnsupportedSessionCreationAttributeWithExternallyDefinedSecurityContextRepository() throws Exception {
        setContext(
                "<b:bean id='repo' class='" + HttpSessionSecurityContextRepository.class.getName() + "'/>" +
                "<http create-session='never' security-context-repository-ref='repo'>" +
                "    <http-basic />" +
                "</http>" + AUTH_PROVIDER_XML);
    }

    @Test
    public void expressionBasedAccessAllowsAndDeniesAccessAsExpected() throws Exception {
        setContext(
                "    <http auto-config='true' use-expressions='true'>" +
                "        <intercept-url pattern='/secure*' access=\"hasRole('ROLE_A')\" />" +
                "        <intercept-url pattern='/**' access='permitAll()' />" +
                "    </http>" + AUTH_PROVIDER_XML);

        FilterSecurityInterceptor fis = (FilterSecurityInterceptor) appContext.getBean(BeanIds.FILTER_SECURITY_INTERCEPTOR);

        FilterInvocationSecurityMetadataSource fids = fis.getSecurityMetadataSource();
        List<? extends ConfigAttribute> attrDef = fids.getAttributes(createFilterinvocation("/secure", null));
        assertEquals(1, attrDef.size());

        // Try an unprotected invocation
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("joe", "", "ROLE_A"));
        fis.invoke(createFilterinvocation("/permitallurl", null));
        // Try secure Url as a valid user
        fis.invoke(createFilterinvocation("/securex", null));
        // And as a user without the required role
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("joe", "", "ROLE_B"));
        try {
            fis.invoke(createFilterinvocation("/securex", null));
            fail("Expected AccessDeniedInvocation");
        } catch (AccessDeniedException expected) {
        }
    }

    @Test
    public void customSuccessAndFailureHandlersCanBeSetThroughTheNamespace() throws Exception {
        setContext(
                "<http>" +
                "   <form-login authentication-success-handler-ref='sh' authentication-failure-handler-ref='fh'/>" +
                "</http>" +
                "<b:bean id='sh' class='" + SavedRequestAwareAuthenticationSuccessHandler.class.getName() +"'/>" +
                "<b:bean id='fh' class='" + SimpleUrlAuthenticationFailureHandler.class.getName() + "'/>" +
                AUTH_PROVIDER_XML);
        AuthenticationProcessingFilter apf = (AuthenticationProcessingFilter) appContext.getBean(BeanIds.FORM_LOGIN_FILTER);
        AuthenticationSuccessHandler sh = (AuthenticationSuccessHandler) appContext.getBean("sh");
        AuthenticationFailureHandler fh = (AuthenticationFailureHandler) appContext.getBean("fh");
        assertSame(sh, FieldUtils.getFieldValue(apf, "successHandler"));
        assertSame(fh, FieldUtils.getFieldValue(apf, "failureHandler"));
    }

    @Test
    public void disablingUrlRewritingThroughTheNamespaceSetsCorrectPropertyOnContextRepo() throws Exception {
        setContext("<http auto-config='true' disable-url-rewriting='true'/>" + AUTH_PROVIDER_XML);
        Object filter = appContext.getBean(BeanIds.SECURITY_CONTEXT_PERSISTENCE_FILTER);
        assertEquals(Boolean.TRUE, FieldUtils.getFieldValue(filter, "repo.disableUrlRewriting"));
    }

    private void setContext(String context) {
        appContext = new InMemoryXmlApplicationContext(context);
    }

    @SuppressWarnings("unchecked")
    private List<Filter> getFilters(String url) throws Exception {
        FilterChainProxy fcp = (FilterChainProxy) appContext.getBean(BeanIds.FILTER_CHAIN_PROXY);
        Method getFilters = fcp.getClass().getDeclaredMethod("getFilters", String.class);
        getFilters.setAccessible(true);
        return (List<Filter>) ReflectionUtils.invokeMethod(getFilters, fcp, new Object[] {url});
    }

    private FilterInvocation createFilterinvocation(String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(null);

        request.setServletPath(path);

        return new FilterInvocation(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}