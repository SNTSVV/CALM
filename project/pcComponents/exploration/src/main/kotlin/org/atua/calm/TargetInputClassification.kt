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
import org.atua.modelFeatures.ewtg.WindowManager
import java.nio.file.Files
import java.nio.file.Paths

class TargetInputClassification {
    val identifiedTargetInputsByStaticAnalysis =  HashMap<Input, HashMap<String,Boolean>>()
    val identifiedTargetInputsByBaseModel =  HashMap<Input, HashMap<String,Boolean>>()
    val runtimeIdentifiedTargetInputs = HashMap<Input, HashMap<String,Boolean>>()
    val positiveTargetInputs = HashSet<Input>()

    fun writeReport(filePathString: String) {
        ATUAMF.log.info("Producing Target Identification report...")
        val allInputs = WindowManager.instance.updatedModelWindows.map { it.inputs }.flatten()
        val sb = StringBuilder()
        sb.appendLine("input;isWidgetInput;version;createdAtRunTime;witnessed;isTarget;truePostiveTargetInput;falsePositiveTargetInput")
        allInputs.forEach { input ->
            var isTruePositive = false
            var isFalsePostive = false
            var isTarget = false
            if (input.modifiedMethods.any{it.value}) {
                isTarget = true
            }
            if (identifiedTargetInputsByStaticAnalysis.containsKey(input)) {
                if (identifiedTargetInputsByStaticAnalysis[input]!!.any { it.value }) {
                    isTruePositive = true
                } else {
                    isFalsePostive = true
                }
            }
            sb.appendLine("$input;${input.widget!=null};${input.modelVersion};${input.createdAtRuntime};${input.witnessed};$isTarget;$isTruePositive;$isFalsePostive")
        }
        Files.write(Paths.get(filePathString), sb.lines())
        ATUAMF.log.info("Finished writing target identification report in ${Paths.get(filePathString).fileName}")
    }
    companion object {
        val INSTANCE:  TargetInputClassification by lazy {
            TargetInputClassification()
        }
    }
}