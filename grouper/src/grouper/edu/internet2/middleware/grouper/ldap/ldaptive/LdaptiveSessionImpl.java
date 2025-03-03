/**
 * Copyright 2014 Internet2
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.internet2.middleware.grouper.ldap.ldaptive;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.ldaptive.AddOperation;
import org.ldaptive.AddRequest;
import org.ldaptive.AttributeModification;
import org.ldaptive.AttributeModificationType;
import org.ldaptive.BindConnectionInitializer;
import org.ldaptive.BindOperation;
import org.ldaptive.BindRequest;
import org.ldaptive.CompareRequest;
import org.ldaptive.Connection;
import org.ldaptive.ConnectionConfig;
import org.ldaptive.Credential;
import org.ldaptive.DefaultConnectionFactory;
import org.ldaptive.DeleteOperation;
import org.ldaptive.DeleteRequest;
import org.ldaptive.LdapAttribute;
import org.ldaptive.LdapEntry;
import org.ldaptive.LdapException;
import org.ldaptive.ModifyDnOperation;
import org.ldaptive.ModifyDnRequest;
import org.ldaptive.ModifyOperation;
import org.ldaptive.ModifyRequest;
import org.ldaptive.Response;
import org.ldaptive.ResultCode;
import org.ldaptive.SearchFilter;
import org.ldaptive.SearchOperation;
import org.ldaptive.SearchRequest;
import org.ldaptive.SearchResult;
import org.ldaptive.SearchScope;
import org.ldaptive.control.util.PagedResultsClient;
import org.ldaptive.handler.SearchEntryHandler;
import org.ldaptive.pool.BlockingConnectionPool;
import org.ldaptive.pool.CompareValidator;
import org.ldaptive.pool.IdlePruneStrategy;
import org.ldaptive.pool.PoolConfig;
import org.ldaptive.pool.PooledConnectionFactory;
import org.ldaptive.pool.SearchValidator;
import org.ldaptive.pool.Validator;
import org.ldaptive.props.BindConnectionInitializerPropertySource;
import org.ldaptive.props.ConnectionConfigPropertySource;
import org.ldaptive.props.DefaultConnectionFactoryPropertySource;
import org.ldaptive.props.PoolConfigPropertySource;
import org.ldaptive.props.SearchRequestPropertySource;
import org.ldaptive.provider.jndi.JndiProviderConfig;
import org.ldaptive.referral.AddReferralHandler;
import org.ldaptive.referral.DeleteReferralHandler;
import org.ldaptive.referral.ModifyDnReferralHandler;
import org.ldaptive.referral.ModifyReferralHandler;
import org.ldaptive.referral.SearchReferralHandler;
import org.ldaptive.sasl.GssApiConfig;

import edu.internet2.middleware.grouper.app.loader.GrouperLoaderConfig;
import edu.internet2.middleware.grouper.ldap.LdapConfiguration;
import edu.internet2.middleware.grouper.ldap.LdapHandler;
import edu.internet2.middleware.grouper.ldap.LdapHandlerBean;
import edu.internet2.middleware.grouper.ldap.LdapModificationItem;
import edu.internet2.middleware.grouper.ldap.LdapModificationType;
import edu.internet2.middleware.grouper.ldap.LdapPEMSocketFactory;
import edu.internet2.middleware.grouper.ldap.LdapSearchScope;
import edu.internet2.middleware.grouper.ldap.LdapSession;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.morphString.Morph;
/**
 * will handle the ldap config, and inverse of control for pooling
 * 
 * @author mchyzer
 *
 */
public class LdaptiveSessionImpl implements LdapSession {

  /** map of connection name to pool */
  private static Map<String, PooledConnectionFactory> poolMap = new HashMap<String, PooledConnectionFactory>();
  
  /** map of connection name to properties */
  private static Map<String, Properties> propertiesMap = new HashMap<String, Properties>();
  
  /** 
   * What ldaptive properties will be decrypted if their values are Morph files?
   * (We don't decrypt all properties because that would prevent the use of slashes in the property values)
   **/
  public static final String ENCRYPTABLE_LDAPTIVE_PROPERTIES[] = new String[]{"org.ldaptive.bindCredential"};
  
  private static Map<String, LinkedHashSet<Class<SearchEntryHandler>>> searchEntryHandlers = new HashMap<String, LinkedHashSet<Class<SearchEntryHandler>>>();
  
  private static boolean hasWarnedAboutMissingDnAttributeForSearches = false;
  
  /**
   * get or create the pool based on the server id
   * @param ldapServerId
   * @return the pool
   */
  @SuppressWarnings("unchecked")
  private static PooledConnectionFactory blockingLdapPool(String ldapServerId) {
    
    PooledConnectionFactory blockingLdapPool = poolMap.get(ldapServerId);
    
    if (blockingLdapPool == null) {
      synchronized (LdaptiveSessionImpl.class) {
        blockingLdapPool = poolMap.get(ldapServerId);
        
        if (blockingLdapPool == null) {
          
          BlockingConnectionPool result;
          
          Properties ldaptiveProperties = getLdaptiveProperties(ldapServerId);
          propertiesMap.put(ldapServerId, ldaptiveProperties);
          
          // search result handlers
          LinkedHashSet<Class<SearchEntryHandler>> handlers = new LinkedHashSet<Class<SearchEntryHandler>>();
          String handlerNames = GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".searchResultHandlers");
          if (!StringUtils.isBlank(handlerNames)) {
            String[] handlerClassNames = GrouperUtil.splitTrim(handlerNames, ",");
            for (String className : handlerClassNames) {
              if (className.equals("edu.internet2.middleware.grouper.ldap.handler.RangeSearchResultHandler")) {
                className = "edu.internet2.middleware.grouper.ldap.ldaptive.GrouperRangeEntryHandler";
              } else if (className.equals("edu.vt.middleware.ldap.handler.EntryDnSearchResultHandler")) {
                className = "org.ldaptive.handler.DnAttributeEntryHandler";
              } else if (className.equals("edu.vt.middleware.ldap.handler.FqdnSearchResultHandler")) {
                // ldaptive already gives back the full dn so hopefully we don't have to do anything here.
                continue;
              } else if (className.equals("edu.vt.middleware.ldap.handler.BinarySearchResultHandler")) {
                // ldaptive already handles binary attributes separately so maybe this isn't needed?? need to check
                continue;
              }
              Class<SearchEntryHandler> customClass = GrouperUtil.forName(className);
              handlers.add(customClass);
            }
          }
          
          searchEntryHandlers.put(ldapServerId, handlers);

          // Setup ldaptive ConnectionConfig
          ConnectionConfig connConfig = new ConnectionConfig();
          DefaultConnectionFactory connectionFactory = new DefaultConnectionFactory();

          ConnectionConfigPropertySource ccpSource = new ConnectionConfigPropertySource(connConfig, ldaptiveProperties);
          ccpSource.initialize();
          
          /////////////
          // Binding
          BindConnectionInitializer binder = new BindConnectionInitializer();

          BindConnectionInitializerPropertySource bcip = new BindConnectionInitializerPropertySource(binder, ldaptiveProperties);
          bcip.initialize();
        
          // I'm not sure if SaslRealm and/or SaslAuthorizationId can be used independently
          // Therefore, we'll initialize gssApiConfig when either one of them is used.
          // And, then, we'll attach the gssApiConfig to the binder if there is a gssApiConfig
          GssApiConfig gssApiConfig = null;
          String val = (String) ldaptiveProperties.get("org.ldaptive.saslRealm");
          if (!StringUtils.isBlank(val)) {
            LOG.info("Processing saslRealm");
            if ( gssApiConfig == null )
              gssApiConfig = new GssApiConfig();
            gssApiConfig.setRealm(val);
          }
          
          val = (String) ldaptiveProperties.get("org.ldaptive.saslAuthorizationId");
          if (!StringUtils.isBlank(val)) {
            LOG.info("Processing saslAuthorizationId");
            if ( gssApiConfig == null )
              gssApiConfig = new GssApiConfig();
            gssApiConfig.setAuthorizationId(val);
          }
        
          // If there was a sasl/gssapi attribute, then save the gssApiConfig
          if ( gssApiConfig != null ) {
            LOG.info("Setting gssApiConfig");
            binder.setBindSaslConfig(gssApiConfig);
          }
          
          // handle ssl socket factory
          String cafile = GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".pemCaFile");
          String certfile = GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".pemCertFile");
          String keyfile = GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".pemKeyFile");
          if (cafile != null && certfile != null && keyfile != null) {
            LdapPEMSocketFactory sf = new LdapPEMSocketFactory(cafile, certfile, keyfile);
            SSLSocketFactory ldapSocketFactory = sf.getSocketFactory();
            ((JndiProviderConfig) connectionFactory.getProvider().getProviderConfig()).setSslSocketFactory(ldapSocketFactory);
          }
          
          connConfig.setConnectionInitializer(binder);
          
          DefaultConnectionFactoryPropertySource dcfSource = new DefaultConnectionFactoryPropertySource(connectionFactory, ldaptiveProperties);
          dcfSource.initialize();
          connectionFactory.setConnectionConfig(connConfig);
          
          LinkedHashSet<ResultCode> codesToIgnore = new LinkedHashSet<ResultCode>();
          String searchIgnoreResultCodes = GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".searchIgnoreResultCodes", "SIZE_LIMIT_EXCEEDED");
          if (!StringUtils.isBlank(searchIgnoreResultCodes)) {
            String[] searchIgnoreResultCodesArray = GrouperUtil.splitTrim(searchIgnoreResultCodes, ",");
            for (String searchIgnoreResultCode : searchIgnoreResultCodesArray) {
              codesToIgnore.add(ResultCode.valueOf(searchIgnoreResultCode));
            }
          }
          ((JndiProviderConfig) connectionFactory.getProvider().getProviderConfig()).setSearchIgnoreResultCodes(codesToIgnore.toArray(new ResultCode[]{}));
          

          // batch size
          int batchSize = GrouperLoaderConfig.retrieveConfig().propertyValueInt("ldap." + ldapServerId + ".batchSize", -1);
          if (batchSize > -1) {
            connectionFactory.getProvider().getProviderConfig().getProperties().put("java.naming.batchsize", "" + batchSize);
          }
                    
          //((org.ldaptive.BindConnectionInitializer)connectionFactory.getConnectionConfig().getConnectionInitializer()).setBindDn("");

          /////////////
          // PoolConfig
          
          PoolConfig ldapPoolConfig = new PoolConfig();
          PoolConfigPropertySource pcps = new PoolConfigPropertySource(ldapPoolConfig, ldaptiveProperties);
          pcps.initialize();

          result = new BlockingConnectionPool(ldapPoolConfig, connectionFactory);
          
          int pruneTimerPeriod = GrouperLoaderConfig.retrieveConfig().propertyValueInt("ldap." + ldapServerId + ".pruneTimerPeriod", 300000);
          int expirationTime = GrouperLoaderConfig.retrieveConfig().propertyValueInt("ldap." + ldapServerId + ".expirationTime", 600000);
          
          int validateTimerPeriod = GrouperLoaderConfig.retrieveConfig().propertyValueInt("ldap." + ldapServerId + ".validateTimerPeriod", 0);
          if (validateTimerPeriod > 0) {
            ldapPoolConfig.setValidatePeriod(validateTimerPeriod / 1000);
          }
          
          result.setPruneStrategy(new IdlePruneStrategy(pruneTimerPeriod / 1000, expirationTime / 1000));
          
          Validator<Connection> validator = null;

          String ldapValidator = GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".validator", "SearchValidator");

          if (StringUtils.equalsIgnoreCase(ldapValidator, CompareValidator.class.getSimpleName())
              || StringUtils.equalsIgnoreCase(ldapValidator, "CompareLdapValidator")) {
            String validationDn = GrouperLoaderConfig.retrieveConfig().propertyValueStringRequired("ldap." + ldapServerId + ".validatorCompareDn");
            String validationAttribute = GrouperLoaderConfig.retrieveConfig().propertyValueStringRequired("ldap." + ldapServerId + ".validatorCompareAttribute");
            String validationValue = GrouperLoaderConfig.retrieveConfig().propertyValueStringRequired("ldap." + ldapServerId + ".validatorCompareValue");
            validator = new CompareValidator(new CompareRequest(validationDn, new LdapAttribute(validationAttribute, validationValue)));
          } else if (StringUtils.equalsIgnoreCase(ldapValidator, SearchValidator.class.getSimpleName())) {
            validator = new SearchValidator();
          }
          
          if (validator != null) {
            result.setValidator(validator);
            
            // Make sure some kind of validation is turned on
            if ( !ldapPoolConfig.isValidateOnCheckIn() &&
                 !ldapPoolConfig.isValidateOnCheckOut() &&
                 !ldapPoolConfig.isValidatePeriodically() ) {
              ldapPoolConfig.setValidateOnCheckOut(true);
            }
          }

          result.initialize();
                    
          blockingLdapPool = new PooledConnectionFactory(result);

          poolMap.put(ldapServerId, blockingLdapPool);
        }
      }
    }
    return blockingLdapPool;
  }
  
  private static Properties getLdaptiveProperties(String ldapSystemName) {
    Properties _ldaptiveProperties = new Properties();
    String ldapPropertyPrefix = "ldap." + ldapSystemName + ".";

    _ldaptiveProperties.setProperty("org.ldaptive.bindDn", "");
    
    // load this ldaptive config file before the configs here.  load from classpath
    String configFileFromClasspathParam = ldapPropertyPrefix + "configFileFromClasspath";
    String configFileFromClasspathValue = GrouperLoaderConfig.retrieveConfig().propertyValueString(configFileFromClasspathParam);
    if (!StringUtils.isBlank(configFileFromClasspathValue)) {
      URL url = GrouperUtil.computeUrl(configFileFromClasspathValue, false);
      try {
        _ldaptiveProperties.load(url.openStream());
      } catch (IOException ioe) {
        throw new RuntimeException("Error processing classpath file: " + configFileFromClasspathValue, ioe);
      }
    }
    
    for (String propName : GrouperLoaderConfig.retrieveConfig().propertyNames()) {
      if ( propName.startsWith(ldapPropertyPrefix) ) {
        String propValue = GrouperLoaderConfig.retrieveConfig().propertyValueString(propName, "");

        // Get the part of the property after ldapPropertyPrefix 'ldap.person.'
        String propNameTail = propName.substring(ldapPropertyPrefix.length());
        
        if (propValue == null) {
          propValue = "";
        }
        
        _ldaptiveProperties.put("org.ldaptive." + propNameTail, propValue);

        // Some compatibility between old vtldap properties and ldaptive versions
        // url (vtldap) ==> ldapUrl
        if (propNameTail.equalsIgnoreCase("url")) {
          LOG.info("Setting org.ldaptive.ldapUrl for compatibility with vt-ldap");
          _ldaptiveProperties.put("org.ldaptive.ldapUrl", propValue);
        }
        // tls (vtldap) ==> useStartTls
        if (propNameTail.equalsIgnoreCase("tls")) {
          LOG.info("Setting org.ldaptive.useStartTLS for compatibility with vt-ldap");
          _ldaptiveProperties.put("org.ldaptive.useStartTLS", propValue);
        }
        // user (vtldap) ==> bindDn
        if (propNameTail.equalsIgnoreCase("user")) {
          LOG.info("Setting org.ldaptive.bindDn for compatibility with vt-ldap");
          _ldaptiveProperties.put("org.ldaptive.bindDn", propValue);
        }
        // pass (vtldap) ==> bindCredential
        if (propNameTail.equalsIgnoreCase("pass")) {
          LOG.info("Setting org.ldaptive.bindCredential for compatibility with vt-ldap");
          _ldaptiveProperties.put("org.ldaptive.bindCredential", propValue);
        }
        // countLimit (vtldap) ==> sizeLimit
        if (propNameTail.equalsIgnoreCase("countLimit")) {
          LOG.info("Setting org.ldaptive.sizeLimit for compatibility with vt-ldap");
          _ldaptiveProperties.put("org.ldaptive.sizeLimit", propValue);
        }
        // timeout (vtldap) ==> connectTimeout
        if (propNameTail.equalsIgnoreCase("timeout")) {
          LOG.info("Setting org.ldaptive.connectTimeout for compatibility with vt-ldap");
          _ldaptiveProperties.put("org.ldaptive.connectTimeout", propValue);
        }
      }
    }

    // Go through the properties that can be encrypted and decrypt them if they're Morph files
    for (String encryptablePropertyKey : ENCRYPTABLE_LDAPTIVE_PROPERTIES) {
      String value = _ldaptiveProperties.getProperty(encryptablePropertyKey);
      value = Morph.decryptIfFile(value);
      _ldaptiveProperties.put(encryptablePropertyKey, value);
    }
    return _ldaptiveProperties;
  }
  
  
  /**
   * call this to send a callback for the ldap session object.
   * @param ldapServerId is the config id from the grouper-loader.properties
   * @param ldapHandler is the logic of the ldap calls
   * @return the result of the handler
   */
  private static Object callbackLdapSession(
      String ldapServerId, LdapHandler<Connection> ldapHandler) {
    
    Object ret = null;
    PooledConnectionFactory blockingLdapPool = null;
    Connection ldap = null;
    try {
      
      blockingLdapPool = blockingLdapPool(ldapServerId);

      if (LOG.isDebugEnabled()) {
        LOG.debug("pre-checkout: ldap id: " + ldapServerId + ", pool active: " + blockingLdapPool.getConnectionPool().activeCount() + ", available: " + blockingLdapPool.getConnectionPool().availableCount());
      }

      ldap = blockingLdapPool.getConnection();
      
      if (LOG.isDebugEnabled()) {
        LOG.debug("post-checkout: ldap id: " + ldapServerId + ", pool active: " + blockingLdapPool.getConnectionPool().activeCount() + ", available: " + blockingLdapPool.getConnectionPool().availableCount());
      }
      
      LdapHandlerBean<Connection> ldapHandlerBean = new LdapHandlerBean<Connection>();
      
      ldapHandlerBean.setLdap(ldap);
        
      ret = ldapHandler.callback(ldapHandlerBean);

    } catch (RuntimeException re) {
      GrouperUtil.injectInException(re, "Problem with ldap conection: " + ldapServerId);
      throw re;
    } catch (Exception e) {
      throw new RuntimeException("Problem with ldap conection: " + ldapServerId, e);
    } finally {
      if (ldap != null) {
        try {
          ldap.close();
        } catch (Exception e) {
          // ignore
        }
      }
    }
    return ret;

  }

  /**
   * @see edu.internet2.middleware.grouper.ldap.LdapSession#list(java.lang.Class, java.lang.String, java.lang.String, edu.internet2.middleware.grouper.ldap.LdapSearchScope, java.lang.String, java.lang.String)
   */
  @SuppressWarnings("unchecked")
  public <R> List<R> list(final Class<R> returnType, final String ldapServerId, 
      final String searchDn, final LdapSearchScope ldapSearchScope, final String filter, final String attributeName) {
    
    try {
      
      return (List<R>)callbackLdapSession(ldapServerId, new LdapHandler<Connection>() {
        
        public Object callback(LdapHandlerBean<Connection> ldapHandlerBean) throws LdapException {

          Connection ldap = ldapHandlerBean.getLdap();
          
          SearchResult searchResult = processSearchRequest(ldapServerId, ldap, searchDn, ldapSearchScope, filter, new String[] { attributeName }, null);
          
          List<R> result = new ArrayList<R>();
          for (LdapEntry entry : searchResult.getEntries()) {
            LdapAttribute attribute = entry.getAttribute(attributeName);
            
            if (attribute == null && StringUtils.equals("dn", attributeName)) {
              String nameInNamespace = entry.getDn();
              Object attributeValue = GrouperUtil.typeCast(nameInNamespace, returnType);
              result.add((R)attributeValue);
            } else {
              
              if (attribute != null) {
                for (Object attributeValue : attribute.getStringValues()) {
    
                  attributeValue = GrouperUtil.typeCast(attributeValue, returnType);
                  if (attributeValue != null) {
                    result.add((R)attributeValue);
                  }
                }
              }
            }
          }

          if (LOG.isDebugEnabled()) {
            LOG.debug("Found " + result.size() + " results for serverId: " + ldapServerId + ", searchDn: " + searchDn
              + ", filter: '" + filter + "', returning attribute: " 
              + attributeName + ", some results: " + GrouperUtil.toStringForLog(result, 100) );
          }
          
          return result;
        }
      });
    } catch (RuntimeException re) {
      GrouperUtil.injectInException(re, "Error querying ldap server id: " + ldapServerId + ", searchDn: " + searchDn
          + ", filter: '" + filter + "', returning attribute: " + attributeName);
      throw re;
    }
    
  }

  /**
   * logger 
   */
  private static final Log LOG = GrouperUtil.getLog(LdaptiveSessionImpl.class);

  /**
   * @see edu.internet2.middleware.grouper.ldap.LdapSession#listInObjects(java.lang.Class, java.lang.String, java.lang.String, edu.internet2.middleware.grouper.ldap.LdapSearchScope, java.lang.String, java.lang.String)
   */
  @SuppressWarnings("unchecked")
  public <R> Map<String, List<R>> listInObjects(final Class<R> returnType, final String ldapServerId, 
      final String searchDn, final LdapSearchScope ldapSearchScope, final String filter, final String attributeName) {
    
    try {
      
      return (Map<String, List<R>>)callbackLdapSession(ldapServerId, new LdapHandler<Connection>() {
        
        public Object callback(LdapHandlerBean<Connection> ldapHandlerBean) throws LdapException {
  
          Connection ldap = ldapHandlerBean.getLdap();
                    
          SearchResult searchResult = processSearchRequest(ldapServerId, ldap, searchDn, ldapSearchScope, filter, new String[] { attributeName }, null);
          
          Map<String, List<R>> result = new HashMap<String, List<R>>();
          int subObjectCount = 0;
          for (LdapEntry entry : searchResult.getEntries()) {
            
            List<R> valueResults = new ArrayList<R>();
            String nameInNamespace = entry.getDn();
            
            result.put(nameInNamespace, valueResults);
            
            LdapAttribute attribute = entry.getAttribute(attributeName);
            
            if (attribute != null) {
              for (Object attributeValue : attribute.getStringValues()) {
                
                attributeValue = GrouperUtil.typeCast(attributeValue, returnType);
                if (attributeValue != null) {
                  subObjectCount++;
                  valueResults.add((R)attributeValue);
                }
              }
            }
          }
  
          if (LOG.isDebugEnabled()) {
            LOG.debug("Found " + result.size() + " results, (" + subObjectCount + " sub-results) for serverId: " + ldapServerId + ", searchDn: " + searchDn
              + ", filter: '" + filter + "', returning attribute: " 
              + attributeName + ", some results: " + GrouperUtil.toStringForLog(result, 100) );
          }
          
          return result;
        }
      });
    } catch (RuntimeException re) {
      GrouperUtil.injectInException(re, "Error querying ldap server id: " + ldapServerId + ", searchDn: " + searchDn
          + ", filter: '" + filter + "', returning attribute: " + attributeName);
      throw re;
    }
    
  }

  /**
   * @see edu.internet2.middleware.grouper.ldap.LdapSession#list(java.lang.String, java.lang.String, edu.internet2.middleware.grouper.ldap.LdapSearchScope, java.lang.String, java.lang.String[], java.lang.Long)
   */
  @SuppressWarnings("unchecked")
  public List<edu.internet2.middleware.grouper.ldap.LdapEntry> list(final String ldapServerId, final String searchDn,
      final LdapSearchScope ldapSearchScope, final String filter, final String[] attributeNames, final Long sizeLimit) {

    try {
      
      return (List<edu.internet2.middleware.grouper.ldap.LdapEntry>)callbackLdapSession(ldapServerId, new LdapHandler<Connection>() {
        
        public Object callback(LdapHandlerBean<Connection> ldapHandlerBean) throws LdapException {

          Connection ldap = ldapHandlerBean.getLdap();
                    
          SearchResult searchResults = processSearchRequest(ldapServerId, ldap, searchDn, ldapSearchScope, filter, attributeNames, sizeLimit);
          
          List<edu.internet2.middleware.grouper.ldap.LdapEntry> results = getLdapEntriesFromSearchResult(searchResults, attributeNames);
          
          return results;
        }
      });
    } catch (RuntimeException re) {
      GrouperUtil.injectInException(re, "Error querying ldap server id: " + ldapServerId + ", searchDn: " + searchDn
          + ", filter: '" + filter + "', returning attributes: " + StringUtils.join(attributeNames, ", "));
      throw re;
    }
  }
  
  @SuppressWarnings("unchecked")
  @Override
  public List<edu.internet2.middleware.grouper.ldap.LdapEntry> read(String ldapServerId, String searchDn, List<String> dnList, String[] attributeNames) {
    try {
      return (List<edu.internet2.middleware.grouper.ldap.LdapEntry>)callbackLdapSession(ldapServerId, new LdapHandler<Connection>() {
        
        public Object callback(LdapHandlerBean<Connection> ldapHandlerBean) throws LdapException {

          Connection ldap = ldapHandlerBean.getLdap();
          
          List<edu.internet2.middleware.grouper.ldap.LdapEntry> results = new ArrayList<edu.internet2.middleware.grouper.ldap.LdapEntry>();

          LdapConfiguration config = LdapConfiguration.getConfig(ldapServerId);
          int batchSize = config.getQueryBatchSize();
          
          if (StringUtils.isEmpty(config.getDnAttributeForSearches()) && !hasWarnedAboutMissingDnAttributeForSearches) {
            LOG.warn("Performance impact due to missing config: ldap." + ldapServerId + ".dnAttributeForSearches");
            hasWarnedAboutMissingDnAttributeForSearches = true;
          }

          if (!StringUtils.isEmpty(config.getDnAttributeForSearches()) && batchSize > 1) {
            int numberOfBatches = GrouperUtil.batchNumberOfBatches(GrouperUtil.length(dnList), batchSize);
            for (int i = 0; i < numberOfBatches; i++) {
              List<String> currentBatch = GrouperUtil.batchList(dnList, batchSize, i);
              StringBuilder builder = new StringBuilder();
              for (String dn : currentBatch) {
                builder.append("(" + config.getDnAttributeForSearches() + "=" + dn + ")");
              }
              
              String filter = "(|" + builder.toString() + ")";
              SearchResult searchResults = processSearchRequest(ldapServerId, ldap, searchDn, LdapSearchScope.SUBTREE_SCOPE, filter, attributeNames, null);
              results.addAll(getLdapEntriesFromSearchResult(searchResults, attributeNames));              
            }
          } else {
            for (String dn : dnList) {
              SearchResult searchResults = processSearchRequest(ldapServerId, ldap, dn, LdapSearchScope.OBJECT_SCOPE, "(objectclass=*)", attributeNames, null);
              results.addAll(getLdapEntriesFromSearchResult(searchResults, attributeNames));              
            }
          }
          
          return results;
        }
      });
    } catch (RuntimeException re) {
      GrouperUtil.injectInException(re, "Error querying ldap server id: " + ldapServerId + ", dnList size: " + dnList.size()
          + ", returning attributes: " + StringUtils.join(attributeNames, ", "));
      throw re;
    }
  }
  
  /**
   * @see edu.internet2.middleware.grouper.ldap.LdapSession#authenticate(java.lang.String, java.lang.String, java.lang.String)
   */
  public void authenticate(final String ldapServerId, final String userDn, final String password) {
      
      callbackLdapSession(ldapServerId, new LdapHandler<Connection>() {
        
        public Object callback(LdapHandlerBean<Connection> ldapHandlerBean) throws LdapException {

          Connection ldap = ldapHandlerBean.getLdap();
          
          ConnectionConfig connectionConfig = ConnectionConfig.newConnectionConfig(ldap.getConnectionConfig());
          connectionConfig.setConnectionInitializer(null);
          Connection ldap2 = DefaultConnectionFactory.getConnection(connectionConfig);
          
          try {
            ldap2.open();
            BindOperation bind = new BindOperation(ldap2);
            bind.execute(new BindRequest(userDn, new Credential(password)));
          } finally {
            try {
              ldap2.close();
            } catch (Exception e) {
              // ignore
            }
          }
          
          return null;
        }
      });

  }
  
  private SearchScope translateScope(LdapSearchScope jndiScope) {
    if (jndiScope == null) {
      return null;
    }
    
    SearchScope ldaptiveScope = null;
    
    if (jndiScope == LdapSearchScope.OBJECT_SCOPE) {
      ldaptiveScope = SearchScope.OBJECT;
    } else if (jndiScope == LdapSearchScope.ONELEVEL_SCOPE) {
      ldaptiveScope = SearchScope.ONELEVEL;
    } else if (jndiScope == LdapSearchScope.SUBTREE_SCOPE) {
      ldaptiveScope = SearchScope.SUBTREE;
    } else {
      throw new RuntimeException("Unexpected scope " + jndiScope);
    }
    
    return ldaptiveScope;
  }
  
  private AttributeModificationType translateModificationType(LdapModificationType modificationType) {
    if (modificationType == null) {
      return null;
    }
    
    AttributeModificationType ldaptiveModificationType = null;
    
    if (modificationType == LdapModificationType.ADD_ATTRIBUTE) {
      ldaptiveModificationType = AttributeModificationType.ADD;
    } else if (modificationType == LdapModificationType.REMOVE_ATTRIBUTE) {
      ldaptiveModificationType = AttributeModificationType.REMOVE;
    } else if (modificationType == LdapModificationType.REPLACE_ATTRIBUTE) {
      ldaptiveModificationType = AttributeModificationType.REPLACE;
    } else {
      throw new RuntimeException("Unexpected modification type " + modificationType);
    }
    
    return ldaptiveModificationType;
  }
  
  private SearchResult processSearchRequest(String ldapServerId, Connection ldap, String searchDn, LdapSearchScope ldapSearchScope, String filter, String[] attributeNames, Long sizeLimit) throws LdapException {

    SearchRequest searchRequest = new SearchRequest();
    searchRequest.setSearchFilter(new SearchFilter(filter));
    searchRequest.setReturnAttributes(attributeNames);
    
    if (searchEntryHandlers.get(ldapServerId).size() > 0) {
      SearchEntryHandler[] handlers = new SearchEntryHandler[searchEntryHandlers.get(ldapServerId).size()];
      int count = 0;
      for (Class<SearchEntryHandler> handlerClass : searchEntryHandlers.get(ldapServerId)) {
        handlers[count] = GrouperUtil.newInstance(handlerClass);
        count++;
      }
      
      searchRequest.setSearchEntryHandlers(handlers);
    }
    
    SearchRequestPropertySource srSource = new SearchRequestPropertySource(searchRequest, propertiesMap.get(ldapServerId));
    srSource.initialize();
    
    // add this after the properties get initialized so that this would override if needed
    // note that the searchDn here is relative
    if (StringUtils.isNotBlank(searchDn)) {
      searchRequest.setBaseDn(searchDn);
    }
    
    if (sizeLimit != null) {
      searchRequest.setSizeLimit(sizeLimit);
    }

    if (ldapSearchScope != null) {
      searchRequest.setSearchScope(translateScope(ldapSearchScope));
    }
    
    if ("follow".equals(GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".referral"))) {
      searchRequest.setReferralHandler(new SearchReferralHandler());
    }
    
    SearchResult searchResults;
    Integer pageSize = GrouperLoaderConfig.retrieveConfig().propertyValueInt("ldap." + ldapServerId + ".pagedResultsSize");
    
    if (pageSize == null) {
      SearchOperation search = new SearchOperation(ldap);
      searchResults = search.execute(searchRequest).getResult();
    } else {
      PagedResultsClient client = new PagedResultsClient(ldap, pageSize);
      searchResults = client.executeToCompletion(searchRequest).getResult();
    }
    
    return searchResults;
  }
  
  private List<edu.internet2.middleware.grouper.ldap.LdapEntry> getLdapEntriesFromSearchResult(SearchResult searchResults, String[] attributeNames) {

    List<edu.internet2.middleware.grouper.ldap.LdapEntry> results = new ArrayList<edu.internet2.middleware.grouper.ldap.LdapEntry>();

    for (LdapEntry searchResult : searchResults.getEntries()) {

      String nameInNamespace = searchResult.getDn();
      
      edu.internet2.middleware.grouper.ldap.LdapEntry entry = new edu.internet2.middleware.grouper.ldap.LdapEntry(nameInNamespace);
      for (String attributeName : attributeNames) {
        edu.internet2.middleware.grouper.ldap.LdapAttribute attribute = new edu.internet2.middleware.grouper.ldap.LdapAttribute(attributeName);
        
        LdapAttribute sourceAttribute = searchResult.getAttribute(attributeName);
        if (sourceAttribute != null) {
          if (sourceAttribute.isBinary()) {
            attribute.setBinaryValues(sourceAttribute.getBinaryValues());
          } else {
            attribute.setStringValues(sourceAttribute.getStringValues());
          }
        }
        
        entry.addAttribute(attribute);
      }
      
      results.add(entry);
    }
    
    return results;
  }

  @Override
  public void delete(final String ldapServerId, final String dn) {

    try {
      callbackLdapSession(ldapServerId, new LdapHandler<Connection>() {
        
        public Object callback(LdapHandlerBean<Connection> ldapHandlerBean) throws LdapException {

          Connection ldap = ldapHandlerBean.getLdap();

          DeleteOperation delete = new DeleteOperation(ldap);
          DeleteRequest deleteRequest = new DeleteRequest(dn);
          
          if ("follow".equals(GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".referral"))) {
            deleteRequest.setReferralHandler(new DeleteReferralHandler());
          }
          
          try {
            Response<Void> response = delete.execute(deleteRequest);
            if (response.getResultCode() == ResultCode.SUCCESS) {
              return null;
            } else {
              throw new RuntimeException("Received result code: " + response.getResultCode());
            }
          } catch (LdapException e) {
            
            // note that this only happens if an intermediate context does not exist
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
              return null;
            }
            
            // TODO should we re-query just to be sure?
            throw e;
          }
        }
      });
    } catch (RuntimeException re) {
      GrouperUtil.injectInException(re, "Error deleting entry server id: " + ldapServerId + ", dn: " + dn);
      throw re;
    }
  }
  
  @Override
  public boolean create(final String ldapServerId, final edu.internet2.middleware.grouper.ldap.LdapEntry ldapEntry) {
    
    // if create failed because object is there, then do an update with the attributes that were given
    // some attributes given may have no values and therefore clear those attributes
    // true if created, false if updated

    try {
      return (Boolean)callbackLdapSession(ldapServerId, new LdapHandler<Connection>() {
        
        public Object callback(LdapHandlerBean<Connection> ldapHandlerBean) throws LdapException {

          Connection ldap = ldapHandlerBean.getLdap();
          
          List<LdapAttribute> ldaptiveAttributes = new ArrayList<LdapAttribute>(); // if doing create
          List<AttributeModification> ldaptiveModifications = new ArrayList<AttributeModification>(); // if doing modify
          
          for (edu.internet2.middleware.grouper.ldap.LdapAttribute grouperLdapAttribute : ldapEntry.getAttributes()) {
            LdapAttribute ldaptiveAttribute = new LdapAttribute(grouperLdapAttribute.getName());
            if (grouperLdapAttribute.getStringValues().size() > 0) {
              ldaptiveAttribute.addStringValues(grouperLdapAttribute.getStringValues());
            } else if (grouperLdapAttribute.getBinaryValues().size() > 0) {
              ldaptiveAttribute.addBinaryValues(grouperLdapAttribute.getBinaryValues());
            }
            
            if (ldaptiveAttribute.size() > 0) {
              ldaptiveAttributes.add(ldaptiveAttribute);
            }
            
            ldaptiveModifications.add(new AttributeModification(AttributeModificationType.REPLACE, ldaptiveAttribute));
          }

          AddOperation add = new AddOperation(ldap);
          AddRequest addRequest = new AddRequest();
          addRequest.setDn(ldapEntry.getDn());
          addRequest.setLdapAttributes(ldaptiveAttributes);
          
          if ("follow".equals(GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".referral"))) {
            addRequest.setReferralHandler(new AddReferralHandler());
          }
          
          try {
            Response<Void> response = add.execute(addRequest);
            if (response.getResultCode() == ResultCode.SUCCESS) {
              return true;
            } else {
              throw new RuntimeException("Received result code: " + response.getResultCode());
            }
          } catch (LdapException e) {
            
            // update attributes instead
            if (e.getResultCode() == ResultCode.ENTRY_ALREADY_EXISTS) {
              ModifyOperation modify = new ModifyOperation(ldap);
              ModifyRequest modifyRequest = new ModifyRequest(ldapEntry.getDn(), ldaptiveModifications.toArray(new AttributeModification[] { }));
              
              if ("follow".equals(GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".referral"))) {
                modifyRequest.setReferralHandler(new ModifyReferralHandler());
              }
              
              Response<Void> response = modify.execute(modifyRequest);
              if (response.getResultCode() == ResultCode.SUCCESS) {
                return false;
              } else {
                throw new RuntimeException("Received result code: " + response.getResultCode());
              }
            }
            
            throw e;
          }
        }
      });
    } catch (RuntimeException re) {
      GrouperUtil.injectInException(re, "Error creating entry server id: " + ldapServerId + ", dn: " + ldapEntry.getDn());
      throw re;
    }
  }

  @Override
  public boolean move(final String ldapServerId, final String oldDn, final String newDn) {
    // return true if moved
    // return false if newDn exists and oldDn doesn't
    try {
      return (Boolean)callbackLdapSession(ldapServerId, new LdapHandler<Connection>() {
        
        public Object callback(LdapHandlerBean<Connection> ldapHandlerBean) throws LdapException {

          Connection ldap = ldapHandlerBean.getLdap();

          ModifyDnOperation modifyDn = new ModifyDnOperation(ldap);
          ModifyDnRequest modifyDnRequest = new ModifyDnRequest();
          modifyDnRequest.setDeleteOldRDn(true);
          modifyDnRequest.setDn(oldDn);
          modifyDnRequest.setNewDn(newDn);
          
          if ("follow".equals(GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".referral"))) {
            modifyDnRequest.setReferralHandler(new ModifyDnReferralHandler());
          }
          
          try {
            Response<Void> response = modifyDn.execute(modifyDnRequest);
            if (response.getResultCode() == ResultCode.SUCCESS) {
              return true;
            } else {
              throw new RuntimeException("Received result code: " + response.getResultCode());
            }
          } catch (LdapException e) {
            
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
              // old entry doesn't exist.  if the new one does, then let's assume it was already renamed and return false
              // note that this exception could also happen if the oldDn exists but the newDn is an invalid location - in that case we should still end up throwing the original exception below

              try {
                processSearchRequest(ldapServerId, ldap, newDn, LdapSearchScope.OBJECT_SCOPE, "(objectclass=*)", new String[] { "objectclass" }, null);
                return false;
              } catch (LdapException e2) {
                if (e2.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                  // throw original exception
                  throw e;
                }
                
                // something else went wrong so throw this
                throw e2;
              }
            }   
            
            throw e;
          }
        }
      });
    } catch (RuntimeException re) {
      GrouperUtil.injectInException(re, "Error moving entry server id: " + ldapServerId + ", oldDn: " + oldDn + ", newDn: " + newDn);
      throw re;
    }
  }

  @Override
  public void internal_modifyHelper(final String ldapServerId, String dn, final List<LdapModificationItem> ldapModificationItems) {

    if (ldapModificationItems.size() == 0) {
      return;
    }
    
    try {
      callbackLdapSession(ldapServerId, new LdapHandler<Connection>() {
        
        public Object callback(LdapHandlerBean<Connection> ldapHandlerBean) throws LdapException {

          Connection ldap = ldapHandlerBean.getLdap();
          
          List<AttributeModification> ldaptiveModifications = new ArrayList<AttributeModification>();
          
          for (LdapModificationItem ldapModificationItem : ldapModificationItems) {
            LdapAttribute ldaptiveAttribute = new LdapAttribute(ldapModificationItem.getAttribute().getName());
            if (ldapModificationItem.getAttribute().getStringValues().size() > 0) {
              ldaptiveAttribute.addStringValues(ldapModificationItem.getAttribute().getStringValues());
            } else if (ldapModificationItem.getAttribute().getBinaryValues().size() > 0) {
              ldaptiveAttribute.addBinaryValues(ldapModificationItem.getAttribute().getBinaryValues());
            }

            ldaptiveModifications.add(new AttributeModification(translateModificationType(ldapModificationItem.getLdapModificationType()), ldaptiveAttribute));
          }

          ModifyOperation modify = new ModifyOperation(ldap);
          ModifyRequest modifyRequest = new ModifyRequest(dn, ldaptiveModifications.toArray(new AttributeModification[] { }));
          
          if ("follow".equals(GrouperLoaderConfig.retrieveConfig().propertyValueString("ldap." + ldapServerId + ".referral"))) {
            modifyRequest.setReferralHandler(new ModifyReferralHandler());
          }
          
          Response<Void> response = modify.execute(modifyRequest);
          if (response.getResultCode() == ResultCode.SUCCESS) {
            return null;
          } else {
            throw new RuntimeException("Received result code: " + response.getResultCode());
          }
        }
      });
    } catch (RuntimeException re) {
      GrouperUtil.injectInException(re, "Error modifying entry server id: " + ldapServerId + ", dn: " + dn);
      throw re;
    }
  }
}
