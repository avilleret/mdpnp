/*******************************************************************************
 * Copyright (c) 2012 MD PnP Program.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 ******************************************************************************/
package org.mdpnp.devices.oridion.capnostream;

import ice.AlarmSettings;
import ice.AlarmSettingsObjective;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.mdpnp.devices.EventLoop;
import org.mdpnp.devices.oridion.capnostream.Capnostream.Command;
import org.mdpnp.devices.oridion.capnostream.Capnostream.SetupItem;
import org.mdpnp.devices.serial.AbstractDelegatingSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialSocket;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DemoCapnostream20 extends AbstractDelegatingSerialDevice<Capnostream> {

    // public static final Numeric FAST_STATUS = new
    // NumericImpl(DemoCapnostream20.class, "FAST_STATUS");
    // public static final Numeric SLOW_STATUS = new
    // NumericImpl(DemoCapnostream20.class, "SLOW_STATUS");
    // public static final Numeric CO2_ACTIVE_ALARMS = new
    // NumericImpl(DemoCapnostream20.class, "FAST_STATUS");
    // public static final Numeric SPO2_ACTIVE_ALARMS = new
    // NumericImpl(DemoCapnostream20.class, "FAST_STATUS");
    // public static final Enumeration CAPNOSTREAM_UNITS = new
    // EnumerationImpl(DemoCapnostream20.class, "CAPNOSTREAM_UNITS");
    // public static final Numeric EXTENDED_CO2_STATUS = new
    // NumericImpl(DemoCapnostream20.class, "EXTENDED_CO2_STATUS");

    @Override
    protected long getMaximumQuietTime() {
        return 800L;
    }

    @Override
    protected long getConnectInterval() {
        return 3000L;
    }

    @Override
    protected long getNegotiateInterval() {
        return 200L;
    }

    @Override
    protected String iconResourceName() {
        return "capnostream.png";
    }

    private static final long MAX_COMMAND_RESPONSE = 1500L;

    protected InstanceHolder<ice.SampleArray> co2;
    protected InstanceHolder<ice.Numeric> spo2;
    protected InstanceHolder<ice.Numeric> pulserate;

    protected InstanceHolder<ice.Numeric> rr;
    protected InstanceHolder<ice.Numeric> etco2;
    protected InstanceHolder<ice.Numeric> fastStatus; // = new
                                                      // MutableNumericUpdateImpl(DemoCapnostream20.FAST_STATUS);
    protected InstanceHolder<ice.Numeric> slowStatus; // = new
                                                      // MutableNumericUpdateImpl(DemoCapnostream20.SLOW_STATUS);
    protected InstanceHolder<ice.Numeric> co2ActiveAlarms; // = new
                                                           // MutableNumericUpdateImpl(DemoCapnostream20.CO2_ACTIVE_ALARMS);
    protected InstanceHolder<ice.Numeric> spo2ActiveAlarms; // = new
                                                            // MutableNumericUpdateImpl(DemoCapnostream20.SPO2_ACTIVE_ALARMS);
    protected InstanceHolder<ice.Numeric> extendedCO2Status; // = new
                                                             // MutableNumericUpdateImpl(DemoCapnostream20.EXTENDED_CO2_STATUS);
    // protected final MutableEnumerationUpdate capnostreamUnits = new
    // MutableEnumerationUpdateImpl(DemoCapnostream20.CAPNOSTREAM_UNITS);

    protected InstanceHolder<ice.AlarmSettings> spo2AlarmSettings, pulserateAlarmSettings, rrAlarmSettings, etco2AlarmSettings;



    protected void linkIsActive() {
        try {
            if(ice.ConnectionState.Connected.equals(getState())) {
                // The specification is a bit vague on how quickly unacknowledged commands can be sent
                getDelegate().sendCommand(Command.LinkIsActive);

            }
        } catch (IOException e) {
            log.error("Error sending link is active message", e);
        }
    }

    private final Map<Integer, Integer> currentLow = new HashMap<Integer, Integer>();
    private final Map<Integer, Integer> currentHigh = new HashMap<Integer, Integer>();

    private final Map<Integer, Integer> priorSafeLow = new HashMap<Integer, Integer>();
    private final Map<Integer, Integer> priorSafeHigh = new HashMap<Integer, Integer>();

    @Override
    protected InstanceHolder<AlarmSettings> alarmSettingsSample(InstanceHolder<AlarmSettings> holder, Float newLower,
            Float newUpper, int name) {
        if(newLower != null) {
            currentLow.put(name, (int)(float)newLower);
        }
        if(newUpper != null) {
            currentHigh.put(name, (int)(float)newUpper);
        }
        return super.alarmSettingsSample(holder, newLower, newUpper, name);
    }

    public DemoCapnostream20(int domainId, EventLoop eventLoop) {
        super(domainId, eventLoop);
        init();
    }

    public static SetupItem lowerAlarm(int name) {
        switch(name) {
        case ice.Physio._MDC_PULS_OXIM_SAT_O2:
            return SetupItem.SpO2Low;
        case ice.Physio._MDC_PULS_OXIM_PULS_RATE:
            return SetupItem.PulseRateLow;
        case ice.Physio._MDC_RESP_RATE:
            return SetupItem.respiratoryRateLow;
        case ice.Physio._MDC_AWAY_CO2_EXP:
            return SetupItem.EtCO2Low;
        default:
            return null;
        }
    }

    public static SetupItem upperAlarm(int name) {
        switch(name) {
        case ice.Physio._MDC_PULS_OXIM_SAT_O2:
            return SetupItem.SpO2High;
        case ice.Physio._MDC_PULS_OXIM_PULS_RATE:
            return SetupItem.PulseRateHigh;
        case ice.Physio._MDC_RESP_RATE:
            return SetupItem.respiratoryRateHigh;
        case ice.Physio._MDC_AWAY_CO2_EXP:
            return SetupItem.EtCO2High;
        default:
            return null;
        }
    }


    private final SetupItemHandler setupItemHandler = new SetupItemHandler();

    private final class SetupItemHandler implements Runnable {

        private final Map<SetupItem, Integer> setupValuesToSend = new HashMap<SetupItem, Integer>();
        private SetupItem sent;
        private long sentAt;
        private Integer sentValue;

        public synchronized void send(SetupItem si, Integer value) {
            Integer oldValue = setupValuesToSend.get(si);
            if(null != oldValue) {
                log.debug("Skipping setting " + si + " to " + oldValue + " and now setting to " + value);
            }
            setupValuesToSend.put(si, value);
            executor.schedule(this, 0L, TimeUnit.MILLISECONDS);
        }

        public synchronized void receive(SetupItem si, Integer value) {
            if(si.equals(sent)) {
                if(value.equals(sentValue)) {
                    log.debug("Acknowledged " + si + " = " + value);
                } else {
                    log.warn("Acknowledged " + si + " = " + value + " but actually sent " + sentValue);
                }
                sent = null;
                sentAt = 0L;
                sentValue = null;
            } else {
                log.warn("Not sent but received acknowledgment " + si + " = " + value);
            }
            if(!setupValuesToSend.isEmpty()) {
                executor.schedule(this, 0L, TimeUnit.MILLISECONDS);
            }
        }


        @Override
        public synchronized void run() {
            long now = System.currentTimeMillis();

            if(sent != null) {
                if(now > (sentAt + MAX_COMMAND_RESPONSE)) {
                    log.warn("Timed out waiting for response to " + sent);
                    sent = null;
                    sentValue = null;
                    sentAt = 0L;
                } else {
                    executor.schedule(this, sentAt + MAX_COMMAND_RESPONSE - now, TimeUnit.MILLISECONDS);
                    return;
                }
            }
            if(!setupValuesToSend.isEmpty()) {
                Iterator<SetupItem> itr = setupValuesToSend.keySet().iterator();
                sent = itr.next();
                sentValue = setupValuesToSend.remove(sent);
                try {

                    if(getDelegate().sendConfigurableSetup(sent, sentValue)) {
                        log.debug("Sent " + sent + " = " + sentValue);
                        sentAt = System.currentTimeMillis();
                        executor.schedule(this, MAX_COMMAND_RESPONSE, TimeUnit.MILLISECONDS);
                    } else {
                        log.debug("Did NOT Send " + sent + " = " + sentValue);
                        sentAt = 0L;
                        sent = null;
                        sentValue = null;
                    }
                } catch (IOException e) {
                    log.error("error", e);
                }

            }
        }

    }



    @Override
    public void unsetAlarmSettings(AlarmSettingsObjective obj) {
        super.unsetAlarmSettings(obj);
        log.warn("Resetting " + obj.name + " to [" + priorSafeLow.get(obj.name) + " , " + priorSafeHigh.get(obj.name));
        setupItemHandler.send(lowerAlarm(obj.name), priorSafeLow.get(obj.name));
        setupItemHandler.send(upperAlarm(obj.name), priorSafeHigh.get(obj.name));
    }

    @Override
    public void setAlarmSettings(AlarmSettingsObjective obj) {
        super.setAlarmSettings(obj);
        priorSafeHigh.put(obj.name, currentHigh.get(obj.name));
        priorSafeLow.put(obj.name, currentLow.get(obj.name));
        setupItemHandler.send(lowerAlarm(obj.name), (int) obj.lower);
        setupItemHandler.send(upperAlarm(obj.name), (int) obj.upper);
    }

    private void init() {
        deviceIdentity.manufacturer = "Oridion";
        deviceIdentity.model = "Capnostream20";
        AbstractSimulatedDevice.randomUDI(deviceIdentity);
        writeDeviceIdentity();

        linkIsActive = executor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                linkIsActive();
            }
        }, 5000L, 5000L, TimeUnit.MILLISECONDS);
    }

    public DemoCapnostream20(int domainId, EventLoop eventLoop, SerialSocket serialSocket) {
        super(domainId, eventLoop, serialSocket);
        init();
    }

    private static final int BUFFER_SAMPLES = 10;
    private final Number[] realtimeBuffer = new Number[BUFFER_SAMPLES];
    private int realtimeBufferCount = 0;

    public class MyCapnostream extends Capnostream {
        public MyCapnostream(InputStream in, OutputStream out) {
            super(in, out);
        }

        @Override
        public boolean receiveNumerics(long date, int etCO2, int FiCO2, int respiratoryRate, int spo2, int pulserate,
                int slowStatus, int CO2ActiveAlarms, int SpO2ActiveAlarms, int noBreathPeriodSeconds,
                int etCo2AlarmHigh, int etCo2AlarmLow, int rrAlarmHigh, int rrAlarmLow, int fico2AlarmHigh,
                int spo2AlarmHigh, int spo2AlarmLow, int pulseAlarmHigh, int pulseAlarmLow, CO2Units units,
                int extendedCO2Status) {

            // We have an SpO2 value

            DemoCapnostream20.this.spo2 = numericSample(DemoCapnostream20.this.spo2, 0xFF == spo2 ? null : spo2,
                    ice.Physio.MDC_PULS_OXIM_SAT_O2.value());

            rr = numericSample(rr, 0xFF == respiratoryRate ? null : respiratoryRate, ice.Physio.MDC_RESP_RATE.value());

            etco2 = numericSample(etco2, 0xFF == etCO2 ? null : etCO2, ice.Physio.MDC_AWAY_CO2_EXP.value());

            DemoCapnostream20.this.pulserate = numericSample(DemoCapnostream20.this.pulserate, 0xFF == pulserate ? null
                    : pulserate, ice.Physio.MDC_PULS_OXIM_PULS_RATE.value());

            DemoCapnostream20.this.extendedCO2Status = numericSample(DemoCapnostream20.this.extendedCO2Status,
                    0xFF == extendedCO2Status ? null : extendedCO2Status, oridion.MDC_EXTENDED_CO2_STATUS.VALUE);

            DemoCapnostream20.this.slowStatus = numericSample(DemoCapnostream20.this.slowStatus,
                    0xFF == slowStatus ? null : slowStatus, oridion.MDC_SLOW_STATUS.VALUE);

            DemoCapnostream20.this.co2ActiveAlarms = numericSample(DemoCapnostream20.this.co2ActiveAlarms,
                    0xFF == CO2ActiveAlarms ? null : CO2ActiveAlarms, oridion.MDC_CO2_ACTIVE_ALARMS.VALUE);

            DemoCapnostream20.this.spo2ActiveAlarms = numericSample(DemoCapnostream20.this.spo2ActiveAlarms,
                    0xFF == SpO2ActiveAlarms ? null : SpO2ActiveAlarms, oridion.MDC_SPO2_ACTIVE_ALARMS.VALUE);

            DemoCapnostream20.this.spo2AlarmSettings = alarmSettingsSample(DemoCapnostream20.this.spo2AlarmSettings,
                    0xFF == spo2AlarmLow ? null : (float)spo2AlarmLow, 0xFF == spo2AlarmHigh ? null : (float)spo2AlarmHigh, ice.Physio._MDC_PULS_OXIM_SAT_O2);

            DemoCapnostream20.this.etco2AlarmSettings = alarmSettingsSample(DemoCapnostream20.this.etco2AlarmSettings,
                    0xFF == etCo2AlarmLow ? null : (float)etCo2AlarmLow, 0xFF == etCo2AlarmHigh  ? null : (float) spo2AlarmLow, ice.Physio._MDC_AWAY_CO2_EXP);

            DemoCapnostream20.this.pulserateAlarmSettings = alarmSettingsSample(DemoCapnostream20.this.pulserateAlarmSettings,
                    0xFF == pulseAlarmLow ? null : (float)pulseAlarmLow, 0xFF == pulseAlarmHigh ? null : (float) pulseAlarmHigh, ice.Physio._MDC_PULS_OXIM_PULS_RATE);

            return true;
        }

        @Override
        public boolean receiveCO2Wave(int messageNumber, double co2, int status) {
            reportConnected();
            DemoCapnostream20.this.fastStatus = numericSample(DemoCapnostream20.this.fastStatus,
                    status, oridion.MDC_FAST_STATUS.VALUE);

            if (0 != (0x01 & status)) {
                log.warn("invalid CO2 value ignored " + co2 + " with fast status " + Integer.toHexString(status));
                return true;
            }
            if(0 != (0x02 & status)) {
                log.warn("Initialization " + co2 + " with fast status " + Integer.toHexString(status));
                return true;
            }
            if(0 != (0x04 & status)) {
                log.warn("occlusion " + co2 + " with fast status " + Integer.toHexString(status));
                return true;
            }
            if(0 != (0x08 & status)) {
//                log.debug("End of breath");
            }
            if(0 != (0x10 & status)) {
                // SFM in progress
                log.warn("SFM in progress " + co2 + " with fast status " + Integer.toHexString(status));
                return true;
            }
            if(0 != (0x20 & status)) {
                // purge in progress
                log.warn("purge in progress " + co2 + " with fast status " + Integer.toHexString(status));
                return true;
            }
            if(0 != (0x40 & status)) {
                // filter line not connected
//                if(null != DemoCapnostream20.this.co2) {
//                    unregisterSampleArrayInstance(DemoCapnostream20.this.co2);
//                    DemoCapnostream20.this.co2 = null;
//                }
                log.warn("Filterline indicates disconnected " + co2 + " with fast status " + Integer.toHexString(status));
                return true;
            }
            if(0 != (0x80 & status)) {
                log.warn("CO2 malfunction " + co2 + " with fast status " + Integer.toHexString(status));
                return true;
            }

            realtimeBuffer[realtimeBufferCount++] = co2;
            if (realtimeBufferCount == realtimeBuffer.length) {
                realtimeBufferCount = 0;
                DemoCapnostream20.this.co2 = sampleArraySample(DemoCapnostream20.this.co2, realtimeBuffer, 50,
                        ice.MDC_CAPNOGRAPH.VALUE);

            }
            return true;
        }

        @Override
        public boolean receiveDeviceIdSoftwareVersion(String softwareVersion, Date softwareReleaseDate,
                PulseOximetry pulseOximetry, String revision, String serial_number) {
            deviceIdentity.serial_number = serial_number;
            writeDeviceIdentity();
            setupItemHandler.send(SetupItem.CommIntIndication, 2);
            executor.schedule(new Runnable() {
                public void run() {
                    try {
                        getDelegate().sendHostMonitoringId("ICE");
                        Thread.sleep(100L);
                        getDelegate().sendCommand(Command.StartRTComm);
                    } catch (IOException e) {
                        log.error("error", e);
                    } catch (InterruptedException e) {
                        log.error("interrupted", e);
                    }

                }
            }, 0L, TimeUnit.MILLISECONDS);
            return true;

        }

        @Override
        public boolean receiveConfigurableSetup(SetupItem fromCode, int i) {
            super.receiveConfigurableSetup(fromCode, i);
            setupItemHandler.receive(fromCode, i);
            return true;
        }
    }

    private final Logger log = LoggerFactory.getLogger(DemoCapnostream20.class);

    @Override
    protected Capnostream buildDelegate(InputStream in, OutputStream out) {
        return new MyCapnostream(in, out);
    }

    private ScheduledFuture<?> linkIsActive;

    @Override
    protected void doInitCommands() throws IOException {
        super.doInitCommands();
        getDelegate().sendCommand(Command.EnableComm);
    }

    @Override
    public void disconnect() {

        Capnostream capnostream = getDelegate(false);

        if (null != capnostream) {
            try {
                capnostream.sendCommand(Command.StopRTComm);
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    log.error("Interrupted", e);
                }
                capnostream.sendCommand(Command.DisableComm);
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
        } else {
            log.trace(" was already null in disconnect");
        }
        super.disconnect();
    }

    @Override
    public SerialProvider getSerialProvider() {
        SerialProvider serialProvider = super.getSerialProvider();
        serialProvider.setDefaultSerialSettings(115200, DataBits.Eight, Parity.None, StopBits.One);
        return serialProvider;
    }

    @Override
    protected boolean delegateReceive(Capnostream delegate) throws IOException {
        return delegate.receive();
    }

    @Override
    public void shutdown() {
        super.shutdown();
        if (null != linkIsActive) {
            linkIsActive.cancel(false);
            linkIsActive = null;
        }

    }
}
