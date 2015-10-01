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
package org.apache.brooklyn.entity.database.mysql;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.core.annotation.EffectorParam;
import org.apache.brooklyn.core.effector.EffectorBody;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.feed.ssh.SshFeed;
import org.apache.brooklyn.feed.ssh.SshPollConfig;
import org.apache.brooklyn.feed.ssh.SshPollValue;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.ssh.SshTasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskFactory;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static java.lang.String.format;

public class MySqlNodeImpl extends SoftwareProcessImpl implements MySqlNode {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlNodeImpl.class);

    private SshFeed feed;

    public MySqlNodeImpl() {
    }

    public MySqlNodeImpl(Entity parent) {
        this(MutableMap.of(), parent);
    }

    public MySqlNodeImpl(Map<?, ?> flags) {
        super(flags, null);
    }

    public MySqlNodeImpl(Map<?, ?> flags, Entity parent) {
        super(flags, parent);
    }

    @Override
    public Class<?> getDriverInterface() {
        return MySqlDriver.class;
    }

    @Override
    public MySqlDriver getDriver() {
        return (MySqlDriver) super.getDriver();
    }

    @Override
    public void init() {
        super.init();
        getMutableEntityType().addEffector(EXECUTE_SCRIPT, new EffectorBody<String>() {
            @Override
            public String call(ConfigBag parameters) {
                return executeScript((String) parameters.getStringKey("commands"));
            }
        });
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();
        setAttribute(DATASTORE_URL, format("mysql://%s:%s/", getAttribute(HOSTNAME), getAttribute(MYSQL_PORT)));
        
        /*        
         * TODO status gives us things like:
         *   Uptime: 2427  Threads: 1  Questions: 581  Slow queries: 0  Opens: 53  Flush tables: 1  Open tables: 35  Queries per second avg: 0.239
         * So can extract lots of sensors from that.
         */
        Maybe<SshMachineLocation> machine = Locations.findUniqueSshMachineLocation(getLocations());
        boolean retrieveUsageMetrics = getConfig(RETRIEVE_USAGE_METRICS);

        if (machine.isPresent()) {
            String cmd = getDriver().getStatusCmd();
            feed = SshFeed.builder()
                    .entity(this)
                    .period(Duration.FIVE_SECONDS)
                    .machine(machine.get())
                    .poll(new SshPollConfig<Double>(QUERIES_PER_SECOND_FROM_MYSQL)
                            .command(cmd)
                            .onSuccess(new Function<SshPollValue, Double>() {
                                @Override
                                public Double apply(SshPollValue input) {
                                    String q = Strings.getFirstWordAfter(input.getStdout(), "Queries per second avg:");
                                    if (q == null) return null;
                                    return Double.parseDouble(q);
                                }
                            })
                            .setOnFailureOrException(null)
                            .enabled(retrieveUsageMetrics))
                    .poll(new SshPollConfig<Boolean>(SERVICE_PROCESS_IS_RUNNING)
                            .command(cmd)
                            .setOnSuccess(true)
                            .setOnFailureOrException(false)
                            .suppressDuplicates(true))
                    .build();
        } else {
            LOG.warn("Location(s) {} not an ssh-machine location, so not polling for status; setting serviceUp immediately", getLocations());
            setAttribute(SERVICE_UP, true);
        }
    }

    @Override
    protected void disconnectSensors() {
        if (feed != null) feed.stop();
        super.disconnectSensors();
    }

    public int getPort() {
        return getAttribute(MYSQL_PORT);
    }

    public String getSocketUid() {
        String result = getAttribute(MySqlNode.SOCKET_UID);
        if (Strings.isBlank(result)) {
            result = Identifiers.makeRandomId(6);
            setAttribute(MySqlNode.SOCKET_UID, result);
        }
        return result;
    }

    public String getPassword() {
        String result = getAttribute(MySqlNode.PASSWORD);
        if (Strings.isBlank(result)) {
            result = Identifiers.makeRandomId(6);
            setAttribute(MySqlNode.PASSWORD, result);
        }
        return result;
    }

    @Override
    public String getShortName() {
        return "MySQL";
    }

    @Override
    public String executeScript(String commands) {
        return getDriver().executeScriptAsync(commands).block().getStdout();
    }


    public void migrate(@EffectorParam(name = "locationSpec", description = "Location Spec", nullable = false) String locationSpec) {

        if (ServiceStateLogic.getExpectedState(this) != Lifecycle.RUNNING) {
            // Is it necessary to check if the whole application is healthy?
            throw new RuntimeException("The entity needs to be healthy before the migration starts");
        }

        if (getParent() != null && !getParent().equals(getApplication())) {

            //TODO: Allow nested entites to be migrated
            //If the entity has a parent different to the application root the migration cannot be done right now,
            //as it could lead into problems to deal with hierarchies like SameServerEntity -> Entity

            throw new RuntimeException("Nested entities cannot be migrated right now");
        }

        // Retrieving the location from the catalog.
        Location newLocation = getManagementContext().getLocationRegistry().resolve(locationSpec);

        // TODO: Find a way to check if you're migrating an entity to the exactly same VM.

        LOG.info("Migration process of " + this.getId() + " started.");

        // Save the required references to the old location, mainly the driver to be able to send commands to the old
        // machine, before stopping it in order to transfer the data.
        MySqlDriver oldDriver = getDriver();
        MachineProvisioningLocation oldProvisioningLocation = getProvisioningLocation();
        String oldDbIp = sensors().get(ADDRESS);
        Integer oldDbPort = sensors().get(MYSQL_PORT);
        String dbPassword = sensors().get(PASSWORD);


        // Clearing old locations to remove the relationship with the previous instance
        clearLocations();
        addLocations(Lists.newArrayList(newLocation));

        // Starting the new instance
        start(getLocations());

        // Workaround to refresh the sensors, to retrieve the new datastore URL and migrate the data from the old location to the new one
        disconnectSensors();
        connectSensors();

        String newIp = sensors().get(ADDRESS);
        Integer newPort = sensors().get(MYSQL_PORT);

        synchronizeData(oldDriver, oldDbIp, oldDbPort, newIp, newPort, dbPassword);

        // When we have the new location, we free the resources of the current instance
        oldProvisioningLocation.release((MachineLocation) oldDriver.getLocation());

        // Refresh all the dependant entities
        refreshDependantEntities();

        LOG.info("Migration process of " + this.getId() + " finished.");

    }

    private void synchronizeData(MySqlDriver oldDriver, String oldIp, Integer oldPort, String newIp, Integer newPort, String dbPassword) {
        Maybe<SshMachineLocation> sshMachineLocation = Locations.findUniqueSshMachineLocation(getLocations());

        if (sshMachineLocation.isPresent()) {
            MySqlSshDriver driver = (MySqlSshDriver) getDriver();

            String command = format("%s/bin/mysqldump --all-databases -h%s -P%s -uroot -p%s | %s/bin/mysql  -h%s -P%s -uroot -p%s",
                    driver.getBaseDir(), oldIp, oldPort, dbPassword, driver.getBaseDir(), newIp, newPort, dbPassword);

            ProcessTaskFactory<String> mysqlDumpTask = SshTasks.newSshExecTaskFactory(sshMachineLocation.get(), command).requiringZeroAndReturningStdout();
            ProcessTaskWrapper<String> taskResult = Entities.submit(this, mysqlDumpTask).block();

            if (taskResult.getExitCode() != 0) {
                throw new RuntimeException("The database dump failed");
            }

        } else {
            throw new RuntimeException("The machine is not available.");
        }
    }


    /*
      TODO: Find a better way to refresh the application configuration.
    */
    private void refreshDependantEntities() {

        // TODO: Refresh nested entities or find a way to propagate the restart properly.
        for (Entity entity : getApplication().getChildren()) {

            // Restart any entity but the migrated one.
            if (entity instanceof Startable) {
                if (this.equals(entity)) {
                    //The entity is sensors should rewired automatically on stop() + restart()
                } else {
                    // Restart the entity to fetch again all the dependencies (ie. attributeWhenReady ones)
                    ((Startable) entity).restart();
                }
            }
        }

    }

}
