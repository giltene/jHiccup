/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jhiccup;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author sgrinev
 */
public class HiccupConfigurationTest {
    
    @Test
    public void testHelp() {

        HiccupMeter.HiccupMeterConfiguration config = 
                new HiccupMeter.HiccupMeterConfiguration(new String[] {"-h"}, null);
        assertTrue(config.error); // NB: a bug
    }
    
}
