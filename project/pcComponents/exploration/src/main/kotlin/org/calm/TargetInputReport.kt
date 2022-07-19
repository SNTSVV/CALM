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

package org.calm

import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.WindowManager
import java.nio.file.Files
import java.nio.file.Paths

class TargetInputReport {
    val targetIdentifiedByStaticAnalysis = HashSet<Input>()
    val targetIdentifiedByBaseModel = HashSet<Input>()
    val targetRemovedByBaseModel = HashSet<Input>()
    val praticalTargets = HashSet<Input>()

    fun writeReport(filePathString: String) {
        ATUAMF.log.info("Producing Target Identification report...")
        val allInputs = WindowManager.instance.updatedModelWindows.map { it.inputs }.flatten()
        val sb = StringBuilder()
        sb.appendln("input;version;createdAtRunTime;witnessed;identifiedByStaticAnalysis;identifiedByBaseModel;removedByBaseModel;isPractical")
        praticalTargets.forEach {
            val identifiedByStaticAnalysis = targetIdentifiedByStaticAnalysis.contains(it)
            val identifiedByBaseModel = targetIdentifiedByBaseModel.contains(it)
            val removedByBaseModel = targetRemovedByBaseModel.contains(it)
            sb.appendln("$it;${it.modelVersion};${it.createdAtRuntime};${it.witnessed};$identifiedByStaticAnalysis;$identifiedByBaseModel;$removedByBaseModel;true")
        }
        targetIdentifiedByStaticAnalysis.subtract(praticalTargets).forEach {
            val identifiedByBaseModel = targetIdentifiedByBaseModel.contains(it)
            val removedByBaseModel = targetRemovedByBaseModel.contains(it)
            sb.appendln("$it;${it.modelVersion};${it.createdAtRuntime};${it.witnessed};true;$identifiedByBaseModel;$removedByBaseModel;false")
        }
        targetIdentifiedByBaseModel.subtract(targetIdentifiedByStaticAnalysis.union(praticalTargets)).forEach {
            val removedByBaseModel = targetRemovedByBaseModel.contains(it)
            sb.appendln("$it;${it.modelVersion};${it.createdAtRuntime};${it.witnessed};false;true;$removedByBaseModel;false")
        }
        targetRemovedByBaseModel.subtract(targetIdentifiedByStaticAnalysis.union(targetIdentifiedByBaseModel).union(praticalTargets)).forEach {
            sb.appendln("$it;${it.modelVersion};${it.createdAtRuntime};${it.witnessed};false;false;true;false")
        }
        allInputs.subtract(praticalTargets.union(targetIdentifiedByStaticAnalysis).union(targetRemovedByBaseModel).union(targetIdentifiedByBaseModel)).forEach {
            sb.appendln("$it;${it.modelVersion};${it.createdAtRuntime};${it.witnessed};false;false;false;false")
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