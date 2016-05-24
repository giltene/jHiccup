/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jhiccup;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author sgrinev
 */
public class HiccupMeterTest {
    
    public HiccupMeterTest() {
    }
    
    @Before
    public void setUp() {
    }

    @Test
    public void testConfiguration() {

        HiccupMeter.HiccupMeterConfiguration config = 
                new HiccupMeter.HiccupMeterConfiguration(new String[] {"-h"}, null);
        assertTrue(config.error);
    }
    
}
