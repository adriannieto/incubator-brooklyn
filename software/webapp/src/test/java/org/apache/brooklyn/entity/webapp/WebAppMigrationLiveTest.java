/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.entity.webapp;

import com.google.common.collect.ImmutableList;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.ProvisioningLocation;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.core.test.entity.LocalManagementContextForTests;
import org.apache.brooklyn.entity.software.base.AllowsMigration;
import org.apache.brooklyn.test.EntityTestUtils;
import org.apache.brooklyn.test.HttpTestUtils;
import org.apache.brooklyn.test.support.TestResourceUnavailableException;
import org.reflections.Reflections;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// This test could extend AbstractEc2LiveTest but then it will not be able to be parametrized properly.
public class WebAppMigrationLiveTest extends BrooklynAppLiveTestSupport {
    private static final String PROVIDER = "aws-ec2";
    private static final String REGION_NAME = "us-west-2";
    private static final String LOCATION_SPEC = PROVIDER + (REGION_NAME == null ? "" : ":" + REGION_NAME);

    private BrooklynProperties brooklynProperties;
    private ProvisioningLocation amazonProvisioningLocation;

    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUp() throws Exception {
        brooklynProperties = BrooklynProperties.Factory.newDefault();

        // EC2 setup obtained from AbstractEc2LiveTest
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-description-regex");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-name-regex");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-id");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".inboundPorts");
        brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".hardware-id");

        // Also removes scriptHeader (e.g. if doing `. ~/.bashrc` and `. ~/.profile`, then that can cause "stdin: is not a tty")
        brooklynProperties.remove("brooklyn.ssh.config.scriptHeader");
        mgmt = new LocalManagementContextForTests(brooklynProperties);
        amazonProvisioningLocation = (ProvisioningLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);
        super.setUp();
    }


    @Override
    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        app.stop();
        super.tearDown();
    }


    @DataProvider(name = "webAppEntities")
    public Object[][] webAppEntities() {
        Object[][] dataProviderResult;

        Reflections reflections = new Reflections("org.apache.brooklyn.entity.webapp");
        Set<Class<? extends AllowsMigration>> allowMigrationWebApps =
                reflections.getSubTypesOf(AllowsMigration.class);

        List<Object[]> webAppEntities = getEntitiesFromClasses(allowMigrationWebApps);
        dataProviderResult = new Object[webAppEntities.size()][];
        webAppEntities.toArray(dataProviderResult);

        return dataProviderResult;
    }


    private List<Object[]> getEntitiesFromClasses(Set<Class<? extends AllowsMigration>> clazzSet){
        ArrayList<Object[]> entitiyList = new ArrayList<>();

        for (Class clazz : clazzSet) {
            if(clazz.isInterface() && !clazz.getName().contains("JavaWebAppSoftwareProcess")){
                EntitySpec entity = EntitySpec.create(clazz);
                entitiyList.add(new Object[]{entity});
            }

        }


        return entitiyList;
    }
    private String getTestWar() {
        TestResourceUnavailableException.throwIfResourceUnavailable(getClass(), "/hello-world.war");
        return "classpath://hello-world.war";
    }

    @Test(groups = "Live", dataProvider = "webAppEntities")
    public void testMigration(EntitySpec<? extends JavaWebAppSoftwareProcess> webServerSpec) throws Exception {
        final JavaWebAppSoftwareProcess server = app.createAndManageChild(webServerSpec
                .configure(JavaWebAppSoftwareProcess.OPEN_IPTABLES, true)
                .configure("war", getTestWar()));

        // Run the webserver in the premigration location
        app.start(ImmutableList.of(amazonProvisioningLocation));
        EntityTestUtils.assertAttributeEqualsEventually(server, Attributes.SERVICE_UP, Boolean.TRUE);

        // Check if the WebServer is reachable in the premigration location
        String preMigrationUrl = server.getAttribute(JavaWebAppSoftwareProcess.ROOT_URL);
        HttpTestUtils.assertUrlReachable(preMigrationUrl);

        // Start the migration process
        //Effectors.invocation(AllowsMigration.MIGRATE, MutableMap.of("locationSpec", LOCATION_SPEC), server);
        server.migrate(LOCATION_SPEC);
        EntityTestUtils.assertAttributeEqualsEventually(server, Attributes.SERVICE_UP, Boolean.TRUE);

        // Check if the WebServer is reachable in the postmigration location
        String afterMigrationUrl = server.getAttribute(JavaWebAppSoftwareProcess.ROOT_URL);
        HttpTestUtils.assertUrlReachable(afterMigrationUrl);

        // Check if the old location was unprovisioned succesfully
        HttpTestUtils.assertUrlUnreachable(preMigrationUrl);

    }


}
