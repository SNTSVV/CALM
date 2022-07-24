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

package org.atua.modelFeatures

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AttributeType
import org.atua.modelFeatures.dstg.AttributeValuationMap
import org.calm.StringComparison
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.modelFeatures.ModelFeature
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.Widget
import java.lang.StringBuilder
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext

class VerifyTracesMF(val atuamf: ATUAMF) : ModelFeature() {
    override val coroutineContext: CoroutineContext
        get() = CoroutineName("VerifyTracesModelFeature") + Job()

    val obsolescentStates = HashMap<AbstractState,Triple<Interaction<*>,Interaction<*>?,List<Interaction<*>>>>()
    val stateDifferencies = ArrayList<AppStateDiff>()
    override fun onAppExplorationStarted(context: ExplorationContext<*, *, *>) {
        super.onAppExplorationStarted(context)
          atuamf.doNotRefine = true
    }
    override suspend fun onContextUpdate(context: ExplorationContext<*, *, *>) {
        super.onContextUpdate(context)
        atuamf.doNotRefine = true
        atuamf.statementMF!!.onContextUpdate(context)
        atuamf.onContextUpdate(context)
    }

    override suspend fun onAppExplorationFinished(context: ExplorationContext<*, *, *>) {
        this.join()
        super.onAppExplorationFinished(context)
        val sb = StringBuilder()
        obsolescentStates.forEach {
            sb.appendLine("Obsolescent state;${it.key.toString()}")
            sb.appendLine("Corrupt ActionId;${it.value.first.actionId}")
            sb.appendLine("Executed ActionId;${it.value.second?.actionId}")
            sb.appendLine("Missing actionId;${it.value.third.map { it.actionId }.joinToString(",")}")
        }
        stateDifferencies.forEach {
            if (obsolescentStates.containsKey(it.appState2)) {
                sb.appendLine("Observed App State;${it.appState1}")
                sb.appendLine("Expected App State;${it.appState2}")
                sb.appendLine("Differences;${it.differences.size}")
                it.differences.forEach {
                    if (it is PropertyDiff) {
                        sb.appendLine("PropertyDiff;")
                        sb.appendLine("${it.name};${it.value1};${it.value2}")
                    }
                    if (it is AVMDiff) {
                        sb.appendLine("AVMDiff;")
                        sb.appendLine("${it.type};${it.element1};${it.element2}")
                        sb.appendLine("AVMDiffDetails;${it.diffInfos.size}")
                        it.diffInfos.forEach {
                            sb.appendLine("${it.type};${it.name};${it.value1};${it.value2}")
                        }
                    }
                }
            }
        }
        val outputFile = context.model.config.baseDir.resolve("obsolescentStates.txt")
        ATUAMF.log.info(
            "Prepare writing report file: " +
                    "\n- File name: ${outputFile.fileName}" +
                    "\n- Absolute path: ${outputFile.toAbsolutePath().fileName}"
        )

        Files.write(outputFile, sb.lines())
        ATUAMF.log.info("Finished writing report in ${outputFile.fileName}")
    }

    fun computeDifferenceBetweenAbstractStates(observedState: AbstractState, expectedState: AbstractState) {
        if (observedState.window != expectedState.window) {
            return
        }
        val appStateDiff = AppStateDiff(observedState,expectedState, ArrayList())
        stateDifferencies.add(appStateDiff)
        if (observedState.isOpeningMenus != expectedState.isOpeningMenus) {
            val diffDetail = PropertyDiff(name = "isOpeningMenus",
                value1 = observedState.isOpeningMenus.toString(),
                value2 = expectedState.isOpeningMenus.toString())
            appStateDiff.differences.add(diffDetail)
        }
        if (observedState.isOpeningKeyboard != expectedState.isOpeningKeyboard) {
            val diffDetail = PropertyDiff(name = "isOpeningKeyboard",
                value1 = observedState.isOpeningKeyboard.toString(),
                value2 = expectedState.isOpeningKeyboard.toString())
            appStateDiff.differences.add(diffDetail)
        }

        if (observedState.rotation != expectedState.rotation) {
            val diffDetail = PropertyDiff(name = "rotation",
                value1 = observedState.rotation.toString(),
                value2 = expectedState.rotation.toString())
            appStateDiff.differences.add(diffDetail)
        }
        val retainingAVMs1 = ArrayList<AttributeValuationMap>()
        val retainingAVMs2 = ArrayList<AttributeValuationMap>()
        val changedAVMs1 = ArrayList<AttributeValuationMap>()
        val changedAVMs2 = ArrayList<AttributeValuationMap>()
        val observedAVMs = ArrayList(observedState.attributeValuationMaps)
        val expectedAVMs = ArrayList(expectedState.attributeValuationMaps)
        for (observedAVM in observedAVMs) {
            for (expectedAVM in expectedAVMs) {
                if (observedAVM == expectedAVM || observedAVM.hashCode == expectedAVM.hashCode) {
                    retainingAVMs1.add(observedAVM)
                    retainingAVMs2.add(expectedAVM)
                }
            }
        }
        retainingAVMs1.forEach {
            val diffDetail = AVMDiff(type = DiffType.RETAINED,
                element1 = it,
                element2 = null,
                ArrayList()
            )
            appStateDiff.differences.add(diffDetail)
        }
        observedAVMs.removeIf { retainingAVMs1.contains(it) }
        expectedAVMs.removeIf { retainingAVMs2.contains(it) }
        //find the most similar avm
        for (observedAVM in observedAVMs) {
            if (expectedAVMs.isNotEmpty()) {
                val scores = expectedAVMs.associateWith { it.similarScore(observedAVM) }
                val maxScoreItem = scores.maxByOrNull { it.value }!!
                if (maxScoreItem.value>0.5) {
                    val diffDetail = AVMDiff(type = DiffType.CHANGED,
                    element1 = observedAVM,
                    element2 = maxScoreItem.key,
                    ArrayList()
                    )
                    appStateDiff.differences.add(diffDetail)
                    val avmDiffDetails = observedAVM.getDiff(maxScoreItem.key)
                    diffDetail.diffInfos.addAll(avmDiffDetails)
                    changedAVMs1.add(observedAVM)
                    changedAVMs2.add(maxScoreItem.key)
                    expectedAVMs.remove(maxScoreItem.key)
                }
            }
        }
        if (changedAVMs1.isEmpty()) {
            appStateDiff.differences
        }
        observedAVMs.removeIf { changedAVMs1.contains(it) }
        observedAVMs.forEach {
            val diffDetail = AVMDiff(
                type = DiffType.ADDED,
                element1 = it,
                element2 = null,
                diffInfos = ArrayList()
            )
            appStateDiff.differences.add(diffDetail)
        }
        expectedAVMs.forEach {
            val diffDetail = AVMDiff(
                type = DiffType.DELETED,
                element1 = it,
                element2 = null,
                diffInfos = ArrayList()
            )
            appStateDiff.differences.add(diffDetail)
        }

    }
}

private fun AttributeValuationMap.getDiff(comparedAvm: AttributeValuationMap): ArrayList<AVMDiffDetail> {
    val avmDiffs = ArrayList<AVMDiffDetail>()
    this.localAttributes.forEach { attribute,value ->
        if (comparedAvm.localAttributes.containsKey(attribute)) {
            val diffType = if (comparedAvm.localAttributes[attribute]==value) {
                DiffType.RETAINED
            } else {
                DiffType.CHANGED
            }
            avmDiffs.add(
                AVMDiffDetail(name = attribute.toString(),
                    type = diffType,
                    value1 = value,
                    value2 = comparedAvm.localAttributes[attribute]!!))
        } else {
            avmDiffs.add(
                AVMDiffDetail(
                    name = attribute.toString(),
                    type = DiffType.ADDED,
                    value1 = value,
                    value2 = null
                )
            )
        }
    }
    comparedAvm.localAttributes.filter { !this.localAttributes.containsKey(it.key) }.forEach { attribute,value ->
        avmDiffs.add(
            AVMDiffDetail(name = attribute.toString(),
                type = DiffType.DELETED,
                value1 = value,
                value2 = null)
        )
    }
    return avmDiffs
}

private fun AttributeValuationMap.similarScore(comparedAvm: AttributeValuationMap):Double {
    if (this.localAttributes[AttributeType.className]!=comparedAvm.localAttributes[AttributeType.className]) {
        return 0.0
    }
    if (this.localAttributes[AttributeType.xpath]!=comparedAvm.localAttributes[AttributeType.xpath]) {
        return 0.0
    }
    if (this.localAttributes[AttributeType.resourceId]!=comparedAvm.localAttributes[AttributeType.resourceId]) {
         return 0.0
    }
    var propertyCnt = 0
    var totalScore = 0.0
    this.localAttributes.forEach {
        if (it.key in arrayListOf<AttributeType>(AttributeType.checked,AttributeType.clickable,AttributeType.longClickable,AttributeType.scrollable,AttributeType.scrollDirection)) {
            if (comparedAvm.localAttributes.containsKey(it.key)) {
                if (it.value != comparedAvm.localAttributes[it.key]) {
                    propertyCnt++
                    totalScore+=1.0
                } else {
                    propertyCnt++
                    totalScore+=0.0
                }
            }
        }
    }
    this.localAttributes.forEach {
        if (it.key in arrayListOf<AttributeType>(AttributeType.childrenText, AttributeType.siblingsInfo, AttributeType.text)) {
            if (comparedAvm.localAttributes.containsKey(it.key)) {
                val score = StringComparison.compareStringsLevenshtein(it.value,comparedAvm.localAttributes[it.key]!!)
                totalScore += score
                propertyCnt++
            }
        }
    }
    this.localAttributes.forEach {
        if (it.key in arrayListOf<AttributeType>(AttributeType.childrenStructure)) {
            if (comparedAvm.localAttributes.containsKey(it.key)) {
                val score = StringComparison.compareStringsXpathConsine(it.value,comparedAvm.localAttributes[it.key]!!)
                totalScore += score
                propertyCnt++
            }
        }
    }
    return totalScore/propertyCnt
}


data class AppStateDiff (val appState1:AbstractState, val appState2: AbstractState, val differences: ArrayList<AppStateDiffDetail>)

open class AppStateDiffDetail(val name: String) {

}
class PropertyDiff(name:String, val value1: String, val value2: String): AppStateDiffDetail(name= name)
class AVMDiff (val type: DiffType, val element1: AttributeValuationMap, val element2: AttributeValuationMap?, val diffInfos: ArrayList<AVMDiffDetail>):AppStateDiffDetail(name="AVMDiff") {

}

enum class DiffType {
    ADDED,
    DELETED,
    CHANGED,
    RETAINED
}

data class AVMDiffDetail(val name: String, val type: DiffType, val value1: String,val value2: String?) {

}
