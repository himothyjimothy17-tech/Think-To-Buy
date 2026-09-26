package com.qualcomm.robotcore.hardware;

import org.biobuzz.simhooks.SimOnly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Looks up hardware by the name typed into the Driver Station configuration.
 *
 * In the simulator, the "configuration" comes from config/hubs.jsonc. If our
 * code asks for a name that isn't there, we throw the same kind of error the
 * real robot throws, so a typo fails in the sim exactly like on the field.
 */
public class HardwareMap implements Iterable<HardwareDevice> {

    // Type-specific lookups, e.g. hardwareMap.dcMotor.get("frontLeft").
    public DeviceMapping<DcMotorController> dcMotorController = new DeviceMapping<>(DcMotorController.class);
    public DeviceMapping<DcMotor> dcMotor = new DeviceMapping<>(DcMotor.class);
    public DeviceMapping<ServoController> servoController = new DeviceMapping<>(ServoController.class);
    public DeviceMapping<Servo> servo = new DeviceMapping<>(Servo.class);
    public DeviceMapping<CRServo> crservo = new DeviceMapping<>(CRServo.class);
    public DeviceMapping<ColorSensor> colorSensor = new DeviceMapping<>(ColorSensor.class);
    public DeviceMapping<VoltageSensor> voltageSensor = new DeviceMapping<>(VoltageSensor.class);

    public final List<DeviceMapping<? extends HardwareDevice>> allDeviceMappings = new ArrayList<>();

    /** Every device, grouped by the name(s) it was configured with. */
    protected Map<String, List<HardwareDevice>> allDevicesMap = new LinkedHashMap<>();
    protected List<HardwareDevice> allDevicesList = new ArrayList<>();
    protected final Object lock = new Object();

    /** The simulator builds an empty map and fills it from config/hubs.jsonc. */
    @SimOnly
    public HardwareMap() {
        allDeviceMappings.add(dcMotorController);
        allDeviceMappings.add(dcMotor);
        allDeviceMappings.add(servoController);
        allDeviceMappings.add(servo);
        allDeviceMappings.add(crservo);
        allDeviceMappings.add(colorSensor);
        allDeviceMappings.add(voltageSensor);
    }

    /**
     * The most common lookup: {@code hardwareMap.get(DcMotorEx.class, "frontLeft")}.
     * Throws IllegalArgumentException if no device has that name AND type.
     */
    public <T> T get(Class<? extends T> classOrInterface, String deviceName) {
        T result = tryGet(classOrInterface, deviceName);
        if (result == null) {
            throw new IllegalArgumentException(String.format(
                    "Unable to find a hardware device with name \"%s\" and type %s",
                    deviceName, classOrInterface.getSimpleName()));
        }
        return result;
    }

    /** Like get(), but returns null instead of throwing. */
    public <T> T tryGet(Class<? extends T> classOrInterface, String deviceName) {
        synchronized (lock) {
            List<HardwareDevice> list = allDevicesMap.get(deviceName.trim());
            if (list != null) {
                for (HardwareDevice device : list) {
                    if (classOrInterface.isInstance(device)) {
                        return classOrInterface.cast(device);
                    }
                }
            }
            return null;
        }
    }

    /** Looks up a device by name only (any type). */
    public HardwareDevice get(String deviceName) {
        synchronized (lock) {
            List<HardwareDevice> list = allDevicesMap.get(deviceName.trim());
            if (list != null && !list.isEmpty()) {
                return list.get(0);
            }
            throw new IllegalArgumentException(String.format(
                    "Unable to find a hardware device with the name \"%s\"", deviceName));
        }
    }

    /** All devices of a type, e.g. every VoltageSensor. */
    public <T> List<T> getAll(Class<? extends T> classOrInterface) {
        List<T> result = new ArrayList<>();
        synchronized (lock) {
            for (HardwareDevice device : allDevicesList) {
                if (classOrInterface.isInstance(device)) {
                    result.add(classOrInterface.cast(device));
                }
            }
        }
        return result;
    }

    public SortedSet<String> getAllNames(Class<? extends HardwareDevice> classOrInterface) {
        SortedSet<String> names = new TreeSet<>();
        synchronized (lock) {
            for (Map.Entry<String, List<HardwareDevice>> e : allDevicesMap.entrySet()) {
                for (HardwareDevice d : e.getValue()) {
                    if (classOrInterface.isInstance(d)) {
                        names.add(e.getKey());
                    }
                }
            }
        }
        return names;
    }

    /** Adds a device under a name (the simulator calls this while building the robot). */
    public void put(String deviceName, HardwareDevice device) {
        synchronized (lock) {
            String name = deviceName.trim();
            allDevicesMap.computeIfAbsent(name, k -> new ArrayList<>()).add(device);
            if (!allDevicesList.contains(device)) {
                allDevicesList.add(device);
            }
        }
    }

    public boolean remove(String deviceName, HardwareDevice device) {
        synchronized (lock) {
            List<HardwareDevice> list = allDevicesMap.get(deviceName.trim());
            boolean removed = list != null && list.remove(device);
            if (list != null && list.isEmpty()) {
                allDevicesMap.remove(deviceName.trim());
            }
            if (removed) {
                allDevicesList.remove(device);
            }
            return removed;
        }
    }

    public Set<String> getNamesOf(HardwareDevice device) {
        Set<String> names = new LinkedHashSet<>();
        synchronized (lock) {
            for (Map.Entry<String, List<HardwareDevice>> e : allDevicesMap.entrySet()) {
                if (e.getValue().contains(device)) {
                    names.add(e.getKey());
                }
            }
        }
        return names;
    }

    public int size() {
        synchronized (lock) {
            return allDevicesList.size();
        }
    }

    @Override
    public Iterator<HardwareDevice> iterator() {
        synchronized (lock) {
            return new ArrayList<>(allDevicesList).iterator();
        }
    }

    public Iterable<HardwareDevice> unsafeIterable() {
        return Collections.unmodifiableList(allDevicesList);
    }

    public void logDevices() {
        synchronized (lock) {
            for (Map.Entry<String, List<HardwareDevice>> e : allDevicesMap.entrySet()) {
                System.out.println("hardwareMap: " + e.getKey() + " -> " + e.getValue());
            }
        }
    }

    /** A lookup table for one type of device, e.g. hardwareMap.dcMotor. */
    public class DeviceMapping<DEVICE_TYPE extends HardwareDevice> implements Iterable<DEVICE_TYPE> {
        private final Map<String, DEVICE_TYPE> map = new LinkedHashMap<>();
        private final Class<DEVICE_TYPE> deviceTypeClass;

        public DeviceMapping(Class<DEVICE_TYPE> deviceTypeClass) {
            this.deviceTypeClass = deviceTypeClass;
        }

        public Class<DEVICE_TYPE> getDeviceTypeClass() {
            return deviceTypeClass;
        }

        public DEVICE_TYPE cast(Object obj) {
            return deviceTypeClass.cast(obj);
        }

        public DEVICE_TYPE get(String deviceName) {
            synchronized (lock) {
                DEVICE_TYPE device = map.get(deviceName.trim());
                if (device == null) {
                    throw new IllegalArgumentException(String.format(
                            "Unable to find a hardware device with the name \"%s\"", deviceName));
                }
                return device;
            }
        }

        public void put(String deviceName, DEVICE_TYPE device) {
            synchronized (lock) {
                map.put(deviceName.trim(), device);
                HardwareMap.this.put(deviceName, device);
            }
        }

        public void putLocal(String deviceName, DEVICE_TYPE device) {
            synchronized (lock) {
                map.put(deviceName.trim(), device);
            }
        }

        public boolean contains(String deviceName) {
            synchronized (lock) {
                return map.containsKey(deviceName.trim());
            }
        }

        public boolean remove(String deviceName) {
            synchronized (lock) {
                return map.remove(deviceName.trim()) != null;
            }
        }

        @Override
        public Iterator<DEVICE_TYPE> iterator() {
            synchronized (lock) {
                return new ArrayList<>(map.values()).iterator();
            }
        }

        public Set<Map.Entry<String, DEVICE_TYPE>> entrySet() {
            synchronized (lock) {
                return new LinkedHashMap<>(map).entrySet();
            }
        }

        public int size() {
            synchronized (lock) {
                return map.size();
            }
        }
    }
}
