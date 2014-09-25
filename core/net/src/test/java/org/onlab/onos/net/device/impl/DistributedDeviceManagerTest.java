package org.onlab.onos.net.device.impl;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.onos.cluster.DefaultControllerNode;
import org.onlab.onos.cluster.MastershipServiceAdapter;
import org.onlab.onos.cluster.NodeId;
import org.onlab.onos.event.Event;
import org.onlab.onos.event.EventDeliveryService;
import org.onlab.onos.event.impl.TestEventDispatcher;
import org.onlab.onos.net.Device;
import org.onlab.onos.net.DeviceId;
import org.onlab.onos.net.MastershipRole;
import org.onlab.onos.net.Port;
import org.onlab.onos.net.PortNumber;
import org.onlab.onos.net.device.DefaultDeviceDescription;
import org.onlab.onos.net.device.DefaultPortDescription;
import org.onlab.onos.net.device.DeviceAdminService;
import org.onlab.onos.net.device.DeviceDescription;
import org.onlab.onos.net.device.DeviceEvent;
import org.onlab.onos.net.device.DeviceListener;
import org.onlab.onos.net.device.DeviceProvider;
import org.onlab.onos.net.device.DeviceProviderRegistry;
import org.onlab.onos.net.device.DeviceProviderService;
import org.onlab.onos.net.device.DeviceService;
import org.onlab.onos.net.device.PortDescription;
import org.onlab.onos.net.provider.AbstractProvider;
import org.onlab.onos.net.provider.ProviderId;
import org.onlab.onos.store.device.impl.DistributedDeviceStore;
import org.onlab.onos.store.impl.StoreManager;
import org.onlab.onos.store.impl.TestStoreManager;
import org.onlab.packet.IpPrefix;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.onlab.onos.net.Device.Type.SWITCH;
import static org.onlab.onos.net.DeviceId.deviceId;
import static org.onlab.onos.net.device.DeviceEvent.Type.*;

// FIXME This test is slow starting up Hazelcast on each test cases.
// FIXME DistributedDeviceStore should have it's own test cases.

/**
 * Test codifying the device service & device provider service contracts.
 */
public class DistributedDeviceManagerTest {

    private static final ProviderId PID = new ProviderId("of", "foo");
    private static final DeviceId DID1 = deviceId("of:foo");
    private static final DeviceId DID2 = deviceId("of:bar");
    private static final String MFR = "whitebox";
    private static final String HW = "1.1.x";
    private static final String SW1 = "3.8.1";
    private static final String SW2 = "3.9.5";
    private static final String SN = "43311-12345";

    private static final PortNumber P1 = PortNumber.portNumber(1);
    private static final PortNumber P2 = PortNumber.portNumber(2);
    private static final PortNumber P3 = PortNumber.portNumber(3);

    private static final DefaultControllerNode SELF
        = new DefaultControllerNode(new NodeId("foobar"),
                        IpPrefix.valueOf("127.0.0.1"));


    private DeviceManager mgr;

    protected StoreManager storeManager;
    protected DeviceService service;
    protected DeviceAdminService admin;
    protected DeviceProviderRegistry registry;
    protected DeviceProviderService providerService;
    protected TestProvider provider;
    protected TestListener listener = new TestListener();
    private DistributedDeviceStore dstore;
    private TestMastershipManager masterManager;
    private EventDeliveryService eventService;

    @Before
    public void setUp() {
        mgr = new DeviceManager();
        service = mgr;
        admin = mgr;
        registry = mgr;
        // TODO should find a way to clean Hazelcast instance without shutdown.
        Config config = TestStoreManager.getTestConfig();

        masterManager = new TestMastershipManager();

        storeManager = new TestStoreManager(Hazelcast.newHazelcastInstance(config));
        storeManager.activate();

        dstore = new TestDistributedDeviceStore();
        dstore.activate();

        mgr.store = dstore;
        eventService = new TestEventDispatcher();
        mgr.eventDispatcher = eventService;
        mgr.mastershipService = masterManager;
        mgr.activate();

        service.addListener(listener);

        provider = new TestProvider();
        providerService = registry.register(provider);
        assertTrue("provider should be registered",
                   registry.getProviders().contains(provider.id()));
    }

    @After
    public void tearDown() {
        registry.unregister(provider);
        assertFalse("provider should not be registered",
                    registry.getProviders().contains(provider.id()));
        service.removeListener(listener);
        mgr.deactivate();

        dstore.deactivate();
        storeManager.deactivate();
    }

    private void connectDevice(DeviceId deviceId, String swVersion) {
        DeviceDescription description =
                new DefaultDeviceDescription(deviceId.uri(), SWITCH, MFR,
                                             HW, swVersion, SN);
        providerService.deviceConnected(deviceId, description);
        assertNotNull("device should be found", service.getDevice(DID1));
    }

    @Test
    public void deviceConnected() {
        assertNull("device should not be found", service.getDevice(DID1));
        connectDevice(DID1, SW1);
        validateEvents(DEVICE_ADDED);

        assertEquals("only one device expected", 1, Iterables.size(service.getDevices()));
        Iterator<Device> it = service.getDevices().iterator();
        assertNotNull("one device expected", it.next());
        assertFalse("only one device expected", it.hasNext());

        assertEquals("incorrect device count", 1, service.getDeviceCount());
        assertTrue("device should be available", service.isAvailable(DID1));
    }

    @Test
    public void deviceDisconnected() {
        connectDevice(DID1, SW1);
        connectDevice(DID2, SW1);
        validateEvents(DEVICE_ADDED, DEVICE_ADDED, DEVICE_ADDED, DEVICE_ADDED);
        assertTrue("device should be available", service.isAvailable(DID1));

        // Disconnect
        providerService.deviceDisconnected(DID1);
        assertNotNull("device should not be found", service.getDevice(DID1));
        assertFalse("device should not be available", service.isAvailable(DID1));
        validateEvents(DEVICE_AVAILABILITY_CHANGED);

        // Reconnect
        connectDevice(DID1, SW1);
        validateEvents(DEVICE_AVAILABILITY_CHANGED);

        assertEquals("incorrect device count", 2, service.getDeviceCount());
    }

    @Test
    public void deviceUpdated() {
        connectDevice(DID1, SW1);
        validateEvents(DEVICE_ADDED, DEVICE_ADDED);

        connectDevice(DID1, SW2);
        validateEvents(DEVICE_UPDATED, DEVICE_UPDATED);
    }

    @Test
    public void getRole() {
        connectDevice(DID1, SW1);
        assertEquals("incorrect role", MastershipRole.MASTER, service.getRole(DID1));
    }

    @Test
    public void updatePorts() {
        connectDevice(DID1, SW1);
        List<PortDescription> pds = new ArrayList<>();
        pds.add(new DefaultPortDescription(P1, true));
        pds.add(new DefaultPortDescription(P2, true));
        pds.add(new DefaultPortDescription(P3, true));
        providerService.updatePorts(DID1, pds);
        validateEvents(DEVICE_ADDED, DEVICE_ADDED, PORT_ADDED, PORT_ADDED, PORT_ADDED);
        pds.clear();

        pds.add(new DefaultPortDescription(P1, false));
        pds.add(new DefaultPortDescription(P3, true));
        providerService.updatePorts(DID1, pds);
        validateEvents(PORT_UPDATED, PORT_REMOVED);
    }

    @Test
    public void updatePortStatus() {
        connectDevice(DID1, SW1);
        List<PortDescription> pds = new ArrayList<>();
        pds.add(new DefaultPortDescription(P1, true));
        pds.add(new DefaultPortDescription(P2, true));
        providerService.updatePorts(DID1, pds);
        validateEvents(DEVICE_ADDED, DEVICE_ADDED, PORT_ADDED, PORT_ADDED);

        providerService.portStatusChanged(DID1, new DefaultPortDescription(P1, false));
        validateEvents(PORT_UPDATED);
        providerService.portStatusChanged(DID1, new DefaultPortDescription(P1, false));
        assertTrue("no events expected", listener.events.isEmpty());
    }

    @Test
    public void getPorts() {
        connectDevice(DID1, SW1);
        List<PortDescription> pds = new ArrayList<>();
        pds.add(new DefaultPortDescription(P1, true));
        pds.add(new DefaultPortDescription(P2, true));
        providerService.updatePorts(DID1, pds);
        validateEvents(DEVICE_ADDED, DEVICE_ADDED, PORT_ADDED, PORT_ADDED);
        assertEquals("wrong port count", 2, service.getPorts(DID1).size());

        Port port = service.getPort(DID1, P1);
        assertEquals("incorrect port", P1, service.getPort(DID1, P1).number());
        assertEquals("incorrect state", true, service.getPort(DID1, P1).isEnabled());
    }

    @Test
    public void removeDevice() {
        connectDevice(DID1, SW1);
        connectDevice(DID2, SW2);
        assertEquals("incorrect device count", 2, service.getDeviceCount());
        admin.removeDevice(DID1);
        validateEvents(DEVICE_ADDED, DEVICE_ADDED, DEVICE_ADDED, DEVICE_ADDED, DEVICE_REMOVED, DEVICE_REMOVED);
        assertNull("device should not be found", service.getDevice(DID1));
        assertNotNull("device should be found", service.getDevice(DID2));
        assertEquals("incorrect device count", 1, service.getDeviceCount());
    }

    protected void validateEvents(Enum... types) {
        for (Enum type : types) {
            try {
                Event event = listener.events.poll(1, TimeUnit.SECONDS);
                assertNotNull("Timed out waiting for " + event, event);
                assertEquals("incorrect event type", type, event.type());
            } catch (InterruptedException e) {
                fail("Unexpected interrupt");
            }
        }
        assertTrue("Unexpected events left", listener.events.isEmpty());
        listener.events.clear();
    }


    private class TestProvider extends AbstractProvider implements DeviceProvider {
        private Device deviceReceived;
        private MastershipRole roleReceived;

        public TestProvider() {
            super(PID);
        }

        @Override
        public void triggerProbe(Device device) {
        }

        @Override
        public void roleChanged(Device device, MastershipRole newRole) {
            deviceReceived = device;
            roleReceived = newRole;
        }
    }

    private static class TestListener implements DeviceListener {
        final BlockingQueue<DeviceEvent> events = new LinkedBlockingQueue<>();

        @Override
        public void event(DeviceEvent event) {
            events.add(event);
        }
    }

    private class TestDistributedDeviceStore extends DistributedDeviceStore {

        public TestDistributedDeviceStore() {
            this.storeService = storeManager;
        }
    }

    private static class TestMastershipManager extends MastershipServiceAdapter {

        private ConcurrentMap<DeviceId, NodeId> masters = new ConcurrentHashMap<>();

        public TestMastershipManager() {
            // SELF master of all initially
            masters.put(DID1, SELF.id());
            masters.put(DID1, SELF.id());
        }
        @Override
        public MastershipRole getLocalRole(DeviceId deviceId) {
            return MastershipRole.MASTER;
        }

        @Override
        public Set<DeviceId> getDevicesOf(NodeId nodeId) {
            HashSet<DeviceId> set = Sets.newHashSet();
            for (Entry<DeviceId, NodeId> e : masters.entrySet()) {
                if (e.getValue().equals(nodeId)) {
                    set.add(e.getKey());
                }
            }
            return set;
        }

        @Override
        public MastershipRole requestRoleFor(DeviceId deviceId) {
            if (SELF.id().equals(masters.get(deviceId))) {
                return MastershipRole.MASTER;
            } else {
                return MastershipRole.STANDBY;
            }
        }

        @Override
        public void relinquishMastership(DeviceId deviceId) {
            masters.remove(deviceId, SELF.id());
        }
    }
}
