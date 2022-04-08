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

package org.atua.modelFeatures.ewtg.window

import org.atua.calm.modelReuse.ModelHistoryInformation
import org.droidmate.deviceInterface.exploration.Rectangle
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.ewtg.EWTGWidget
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.WindowManager
import org.droidmate.exploration.ExplorationContext

import java.io.File
import java.nio.file.Path

abstract class Window(var classType: String,
                      var windowId: String,
                      val isRuntimeCreated: Boolean,
                      baseModel: Boolean)
{
    var ignored: Boolean = false

    //var activityClass = ""
    val widgets = hashSetOf<EWTGWidget>()
    val inputs = hashSetOf<Input>()
    val mappedStates = hashSetOf<AbstractState>()
    var portraitDimension: Rectangle = Rectangle.empty()
    var landscapeDimension: Rectangle = Rectangle.empty()
    var portraitKeyboardDimension: Rectangle = Rectangle.empty()
    var landscapeKeyboardDimension: Rectangle = Rectangle.empty()
    val windowRuntimeIds = HashSet<String>()

    var meaningfullScore = 200
    init {
        if (!baseModel)
            WindowManager.instance.updatedModelWindows.add(this)
        else
            WindowManager.instance.baseModelWindows.add(this)
    }
    open fun isStatic() = false
    open fun addWidget(EWTGWidget: EWTGWidget): EWTGWidget {
        if (widgets.contains(EWTGWidget))
            return EWTGWidget
        widgets.add(EWTGWidget)
        EWTGWidget.window = this
        return EWTGWidget
    }

    override fun toString(): String {
        return "$classType-$windowId"
    }

    fun dumpStructure(windowsFolder: Path) {
        val obsoleteWidgets = ArrayList<EWTGWidget>()
        val visualizedWidgets = WindowManager.instance.guiWidgetEWTGWidgetMappingByWindow.get(this)?.values?.distinct()?: emptyList()
        this.widgets.filter { !visualizedWidgets.contains(it) }.forEach {
            obsoleteWidgets.add(it)
        }
        File(windowsFolder.resolve("Widgets_$windowId.csv").toUri()).bufferedWriter().use { all ->
            all.write(structureHeader())
            widgets.forEach {
                all.newLine()
                all.write("${it.widgetId};${it.resourceIdName};${it.className};${it.parent?.widgetId};${it.structure};${it.window.classType};${it.createdAtRuntime};${getAttributeValuationSetOrNull(it)};${obsoleteWidgets.contains(it)}")
            }
        }
    }

    private fun getAttributeValuationSetOrNull(it: EWTGWidget) =
            if (it.structure == "")
                null
            else
                it.structure

    fun isTargetWindowCandidate(): Boolean{
        return this !is Launcher
                && this !is OutOfApp
                && (this !is Dialog ||
                (((this as Dialog).dialogType == DialogType.APPLICATION_DIALOG
                                || (this as Dialog).dialogType == DialogType.DIALOG_FRAGMENT)))
                && !(this is Dialog && this.ownerActivitys.all { it is OutOfApp })
    }

    fun dumpEvents(windowsFolder: Path, atuaMF: org.atua.modelFeatures.ATUAMF,eContext: ExplorationContext<*,*,*>) {
        File(windowsFolder.resolve("Events_$windowId.csv").toUri()).bufferedWriter().use { all ->
            all.write(eventHeader())
            inputs.forEach {
                all.newLine()
                /*val currentTraceActions=it.mappingActionIds[eContext.explorationTrace.id.toString()]?.toList()?:emptyList()
                val currentTraceTotalActionCnt = currentTraceActions.size
                var curIncreasingCoverageActionsCnt = 0
                currentTraceActions.forEach { actionId ->
                    var increasingCoverage =  atuaMF.statementMF!!.actionIncreasingCoverageTracking.get(actionId)
                    if (increasingCoverage!=null && increasingCoverage.size>0) {
                        curIncreasingCoverageActionsCnt+=1
                    }
                }*/
                val actionHistInfo = ModelHistoryInformation.INSTANCE.inputUsefulness[it]
                /*val oldTraceActionCnt = actionHistInfo?.first?:0
                val oldIncCovActionCnt = actionHistInfo?.second?:0*/
                val totalActionsCnt = actionHistInfo?.first?:0
                val increasingCoverageActionsCnt = actionHistInfo?.second?:0
                all.write("${it.eventType};${it.widget?.widgetId};${it.sourceWindow.windowId};${it.createdAtRuntime};" +
                        "$totalActionsCnt;$increasingCoverageActionsCnt;" +
                        "\"${it.verifiedEventHandlers.map { atuaMF.statementMF!!.getMethodName(it) }.joinToString(";")}\";" +
                        "\"${it.modifiedMethods.filter { it.value == true }.map { atuaMF.statementMF!!.getMethodName(it.key) }.joinToString(";")}\";" +
                        "\"${it.coveredMethods.filter{it.value}.keys.map { atuaMF.statementMF!!.getMethodName(it)}.joinToString(";")}\";"+
                "${it.isUseless}")
            }
        }
    }
    fun structureHeader(): String {
        return "[1]widgetId;[2]resourceIdName;[3]className;[4]parent;[5]xpath;[6]activity;[7]createdAtRuntime;[8]attributeValuationSetId;[9]isObsolete"
    }
    fun eventHeader(): String {
        return "[1]eventType;[2]widgetId;[3]sourceWindowId;[4]createdAtRuntime;[5]totalActionsCnt;[6]increasingCovetageActionsCnt;[7]eventHandlers;[8]modifiedMethods;[9]coveredMethods;[10]isUseless"
    }

    abstract fun getWindowType(): String
    abstract fun copyToRunningModel(): Window
    fun resetMeaningfulScore() {
        meaningfullScore = 200
    }

    companion object{

    }
}