/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014-2015 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.ozark.ext.jade;

import de.neuland.jade4j.Jade4J.Mode;
import de.neuland.jade4j.JadeConfiguration;
import de.neuland.jade4j.filter.Filter;
import org.glassfish.ozark.engine.ViewEngineConfig;

import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.servlet.ServletContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The JadeOzarkConfiguration. The configuration properties are qualified by
 * <code>org.glassfish.ozark.ext.jade</code>. Following precedence is used:
 * <ol>
 * <li>System Properties</li>
 * <li>Properties defined in a file named
 * <code>jade.properties<code> in the classpath.</li>
 * <li>Defaults</li>
 * </ol>
 *
 * @author Florian Hirsch
 */
public class JadeOzarkConfiguration {

    /**
     * One of HTML, XML, XHTML. Default: XHTML
     *
     * @see {@link Mode}
     */
    public static final String MODE = "org.glassfish.ozark.ext.jade.mode";

    /**
     * Parsed templates will be cached unless this property is set to false.
     */
    public static final String CACHING = "org.glassfish.ozark.ext.jade.caching";

    /**
     * Jade will produce compressed HTML unless this property is set to true.
     */
    public static final String PRETTY_PRINT = "org.glassfish.ozark.ext.jade.prettyPrint";

    /**
     * Qualifier for a {@link Filter} which shall be used by the Jade Engine.
     * The key part after the qualifier will be used as name for the filter. The
     * value should be a fully qualified class name of the filter. Example:      <code>
	 * org.glassfish.ozark.ext.jade.filter.shiny=com.foo.bar.ShinyFilter
     * </code> Jade4J by default registers following filters: cdata, css, js
     *
     * @see https://github.com/neuland/jade4j#filters
     */
    public static final String FILTER_QUALIFIER = "org.glassfish.ozark.ext.jade.filter";

    /**
     * Qualifier for a Helper. The key part after the qualifier will be used as
     * name for the helper. The value should be a fully qualified class name of
     * the filter.      <code>
	 * org.glassfish.ozark.ext.jade.helper.math=com.foo.bar.MathHelper
     * </code>
     *
     * @see https://github.com/neuland/jade4j#helpers
     */
    public static final String HELPER_QUALIFIER = "org.glassfish.ozark.ext.jade.helper";

    /**
     * The encoding used for the templates. Defaults to UTF-8.
     */
    public static final String ENCODING = "org.glassfish.ozark.ext.jade.encoding";

    private Properties configFile;

    @Inject
    private ServletContext servletContext;

    @Produces
    @ViewEngineConfig
    JadeConfiguration produce() {
        loadConfig();
        JadeConfiguration jade = new JadeConfiguration();
        jade.setMode(Mode.valueOf(property(MODE).orElse("XHTML")));
        jade.setCaching(Boolean.valueOf(property(CACHING).orElse("true")));
        jade.setPrettyPrint(Boolean.valueOf(property(PRETTY_PRINT).orElse("false")));
        for (Map.Entry<String, Object> filter : getExtensions(FILTER_QUALIFIER).entrySet()) {
            jade.setFilter(filter.getKey(), (Filter) filter.getValue());
        }
        jade.setSharedVariables(getExtensions(HELPER_QUALIFIER));
        String encoding = property(ENCODING).orElse("UTF-8");
        jade.setTemplateLoader(new ServletContextTemplateLoader(servletContext, encoding));
        return jade;
    }

    private void loadConfig() {
        configFile = new Properties();
        InputStream config = Thread.currentThread().getContextClassLoader().getResourceAsStream("jade.properties");
        if (config != null) {
            try {
                configFile.load(config);
            } catch (IOException ignored) {
                // ignored
            }
        }
    }

    private Optional<String> property(String key) {
        String property = System.getProperty(key, configFile.getProperty(key));
        return Optional.ofNullable(property);
    }

    private Map<String, Object> getExtensions(String qualifier) {
        Map<String, String> properties = new HashMap<>();
        properties.putAll(filterProps(configFile, qualifier));
        properties.putAll(filterProps(System.getProperties(), qualifier));
        Map<String, Object> extensions = new HashMap<>();
        properties.forEach((key, value) -> {
            try {
                Class<?> filter = Class.forName(value);
                extensions.put(key, filter.newInstance());
            } catch (ClassNotFoundException | IllegalAccessException | InstantiationException ex) {
                String msg = String.format("Jade initialization error: Could not register extension '%s' of type %s", key, value);
                throw new IllegalArgumentException(msg, ex);
            }
        });
        return extensions;
    }

    private Map<String, String> filterProps(Properties props, String qualifier) {
        Map<String, String> filters = new HashMap<>();
        props.entrySet().stream()
                .filter(prop -> prop.getKey().toString().startsWith(qualifier))
                .forEach(prop -> {
                    String key = prop.getKey().toString();
                    filters.put(key.substring(key.indexOf(qualifier) + qualifier.length() + 1), prop.getValue().toString());
                });
        return filters;
    }

    void dispose(@Disposes @ViewEngineConfig JadeConfiguration jade) {
        jade.clearCache();
    }
}
