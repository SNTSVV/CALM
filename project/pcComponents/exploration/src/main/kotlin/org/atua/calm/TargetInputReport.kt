/*
 * ATUA is a test automation tool for mobile Apps, which focuses on testing methods updated in each software release.
 * Copyright (C) 2019 - 2021 University of Luxembourg
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.atua.calm

import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.ewtg.Input
import java.nio.file.Files
import java.nio.file.Paths

class TargetInputReport {
    val targetIdentifiedByStaticAnalysis = HashSet<Input>()
    val targetIdentifiedByBaseModel = HashSet<Input>()
    val praticalTargets = HashSet<Input>()

    fun writeReport(filePathString: String) {
        ATUAMF.log.info("Producing Target Identification report...")
        val sb = StringBuilder()
        sb.appendln("input;witnessed;identifiedByStaticAnalysis;identifiedByBaseModel;isPractical")
        praticalTargets.forEach {
            val identifiedByStaticAnalysis = targetIdentifiedByStaticAnalysis.contains(it)
            val identifiedByBaseModel = targetIdentifiedByBaseModel.contains(it)
            sb.appendln("$it;${it.witnessed};$identifiedByStaticAnalysis;$identifiedByBaseModel;true")
        }
        targetIdentifiedByStaticAnalysis.subtract(praticalTargets).forEach {
            val identifiedByBaseModel = targetIdentifiedByBaseModel.contains(it)
            sb.appendln("$it;${it.witnessed};true;$identifiedByBaseModel;false")
        }
        targetIdentifiedByBaseModel.subtract(targetIdentifiedByStaticAnalysis.union(praticalTargets)).forEach {
            sb.appendln("$it;${it.witnessed};false;true;false")
        }
        Files.write(Paths.get(filePathString), sb.lines())
        ATUAMF.log.info("Finished writing target identification report in ${Paths.get(filePathString).fileName}")
    }

    companion object {
        val INSTANCE: TargetInputReport by lazy {
            TargetInputReport()
        }
    }
}