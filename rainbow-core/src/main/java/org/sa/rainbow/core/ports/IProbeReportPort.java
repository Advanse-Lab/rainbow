package org.sa.rainbow.core.ports;

import org.sa.rainbow.translator.probes.IProbeIdentifier;

public interface IProbeReportPort {
    public abstract void reportData (IProbeIdentifier probe, String data);
}