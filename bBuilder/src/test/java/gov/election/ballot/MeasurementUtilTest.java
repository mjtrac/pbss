/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package gov.election.ballot;

import gov.election.ballot.util.MeasurementUtil;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class MeasurementUtilTest {

    @Test
    @DisplayName("72 points = 1 inch")
    void ptToInches() {
        assertThat(MeasurementUtil.ptToInches(72f)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("1 inch = 25.4 mm")
    void inchesToMm() {
        assertThat(MeasurementUtil.inchesToMm(1.0)).isEqualTo(25.4);
    }

    @Test
    @DisplayName("72 points converts to approximately 25.4 mm")
    void ptToMm() {
        assertThat(MeasurementUtil.ptToMm(72f)).isCloseTo(25.4, within(0.001));
    }

    @Test
    @DisplayName("Round-trip: inches -> pt -> inches")
    void roundTripInches() {
        double inches = 3.5;
        float pt = MeasurementUtil.inchesToPt(inches);
        assertThat(MeasurementUtil.ptToInches(pt)).isCloseTo(inches, within(0.001));
    }
}
