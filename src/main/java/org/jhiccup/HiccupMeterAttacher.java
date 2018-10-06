/**
 * Written by Gil Tene of Azul Systems, and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/
 *
 * @author Gil Tene
 */

package org.jhiccup;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import java.io.*;

/**
 * Attach to another process and launch a jHiccup agent in it.
 *
 * Uses HiccupMeter's HiccupMeterConfiguration class to parse and prepare arguments.
 *
 */


public class HiccupMeterAttacher {

    public static void main(final String[] args)  {
        HiccupMeter.HiccupMeterConfiguration config =
                new HiccupMeter.HiccupMeterConfiguration(args, HiccupMeter.defaultHiccupLogFileName);

        if (config.error) {
            System.exit(1);
        }

        if (!config.attachToProcess) {
            System.err.println("HiccupMeterAttacher: must be used with -p option.");
            System.exit(1);
        }

        try {
            // We are supposed to attach to another process and launch a jHiccup agent there, not here.
            if (config.verbose) {
                System.out.println("Attaching to process " + config.pidOfProcessToAttachTo +
                        " and launching jHiccup agent from jar " + config.agentJarFileName +
                        " with args: " + config.agentArgs );
            }
            VirtualMachine vm = VirtualMachine.attach(config.pidOfProcessToAttachTo);
            vm.loadAgent(config.agentJarFileName, config.agentArgs);
            vm.detach();
            System.exit(0);
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        } catch (AttachNotSupportedException ex) {
            ex.printStackTrace();
            System.exit(1);
        } catch (AgentInitializationException ex) {
            ex.printStackTrace();
            System.exit(1);
        } catch ( AgentLoadException ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }
}

