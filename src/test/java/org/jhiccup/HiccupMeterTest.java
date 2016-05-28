/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jhiccup;

import java.lang.management.ManagementFactory;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author sgrinev
 */
public class HiccupMeterTest {
 
    @Before
    public void setUp() {
        System.out.println("Vendor = " + System.getProperty("java.vendor"));
        System.out.println("Version = " + System.getProperty("java.version"));
    }
    
    @Test
    public void testAttach() {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        System.out.println("My pid is " + pid);
        //HiccupMeterAttacher.main(new String[]{"-p", pid, "-j", "/Users/sgrinev/ws/jHiccup/jHiccup.jar"});
    }
}
