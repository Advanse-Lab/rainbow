/*
 * The MIT License
 *
 * Copyright 2014 CMU ABLE Group.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
/**
 * Created April 11, 2007.
 */
package org.sa.rainbow.translator.znn.gauges;

import org.sa.rainbow.core.error.RainbowException;
import org.sa.rainbow.core.gauges.RegularPatternGauge;
import org.sa.rainbow.core.models.commands.IRainbowOperation;
import org.sa.rainbow.core.util.TypedAttribute;
import org.sa.rainbow.core.util.TypedAttributeWithValue;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gauge for estimating end-to-end response time of a server request, as
 * experienced by a proxy client.
 * 
 * @author Shang-Wen Cheng (zensoul@cs.cmu.edu)
 */
public class End2EndRespTimeGauge extends RegularPatternGauge {

    public static final String         NAME              = "G - End-End Resp Time";
    /** Sample window to compute an average latency */
    public static final int AVG_SAMPLE_WINDOW = 5;

    /** List of values reported by this Gauge */
    private static final String[] valueNames = {
            "end2endRespTime(*)"
    };
    private static final String DEFAULT = "DEFAULT";

    private Map<String,Queue<Double>> m_historyMap = null;
    private Map<String,Double> m_cumulationMap = null;

    /**
     * Main constructor.
     */
    public End2EndRespTimeGauge (String id, long beaconPeriod, TypedAttribute gaugeDesc,
            TypedAttribute modelDesc, List<TypedAttributeWithValue> setupParams,
            Map<String, IRainbowOperation> mappings) throws RainbowException {
        super (NAME, id, beaconPeriod, gaugeDesc, modelDesc, setupParams, mappings);

        m_historyMap = new HashMap<> ();
        m_cumulationMap = new HashMap<> ();

        addPattern (DEFAULT, Pattern.compile ("\\[(.+)\\]<(.+)>\\s+(.+?):([0-9.]+)ms"));
    }


    /* (non-Javadoc)
     * @see org.sa.rainbow.translator.gauges.RegularPatternGauge#doMatch(java.lang.String, java.util.regex.Matcher)
     */
    @Override
    protected void doMatch (String matchName, Matcher m) {
        if (matchName == DEFAULT) {
            // acquire the next set of ping RTT data, we care for the average
//			String tstamp = m.group(1);
            String id = m.group (2);
            String host = id.split ("@")[1];
//            String host = m.group(3);
            if (host.equals("")) return;
            double dur = Double.parseDouble(m.group(4));

            // setup data struct for host if new
            if (! m_historyMap.containsKey(host)) {
                m_historyMap.put(host, new LinkedList<Double>());
                m_cumulationMap.put(host, 0.0);
            }
            Queue<Double> history = m_historyMap.get(host);
            double cumulation = m_cumulationMap.get(host);
            // add value to cumulation and enqueue
            cumulation += dur;
            history.offer(dur);
            if (history.size() > AVG_SAMPLE_WINDOW) {
                // if queue size reached window size, then
                //   dequeue and delete oldest value and report average
                cumulation -= history.poll();
            }
            m_cumulationMap.put(host, cumulation);  // store updated cumulation
            dur = cumulation / history.size();
            m_reportingPort.trace (getComponentType (),
                    id () + ": " + cumulation + ", hist" + Arrays.toString (history.toArray ()));

            // update connection in model with latency in seconds
            for (String valueName : valueNames) {
                // massage value name for mapping purposes
                valueName = valueName.replace("*", host);
                if (m_commands.containsKey (valueName)) {
                    // ZNewsSys.c0.experRespTime
                    IRainbowOperation cmd = getCommand (valueName);
                    Map<String, String> parameterMap = new HashMap<> ();
                    parameterMap.put (cmd.getParameters ()[0], Double.toString (dur));
                    issueCommand (cmd, parameterMap);
                }
            }
        }
    }

}
