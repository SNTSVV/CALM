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

import org.calm.modelReuse.ModelHistoryInformation
import org.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.AbstractTransition
import org.atua.modelFeatures.dstg.AttributeType
import org.atua.modelFeatures.dstg.AttributeValuationMap
import org.atua.modelFeatures.dstg.Cardinality
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.dstg.reducer.AbstractionFunction2
import org.atua.modelFeatures.dstg.reducer.DecisionNode2
import org.atua.modelFeatures.ewtg.EWTGWidget
import org.atua.modelFeatures.ewtg.EventType
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.ScrollDirection
import org.atua.modelFeatures.ewtg.WindowManager
import org.atua.modelFeatures.ewtg.window.Activity
import org.atua.modelFeatures.ewtg.window.ContextMenu
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.FakeWindow
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OptionsMenu
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.droidmate.deviceInterface.exploration.Rectangle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.FileReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.collections.HashMap
import kotlin.streams.toList

class AppModelLoader {
    companion object {
        @JvmStatic
        val log: Logger by lazy { LoggerFactory.getLogger(this::class.java) }
        var isSameVersion = false
        fun loadModel(modelPath: Path, isSameVersion: Boolean, autAutMF: org.atua.modelFeatures.ATUAMF) {
            if (!Files.exists(modelPath)) {
                log.debug("Base model does not exist")
                return
            }
            this.isSameVersion = isSameVersion
            /*val actionCoveragePath: Path = modelPath.resolve("actionCoverage.csv")
            log.info("Reading coverage summary.")
            loadActionCoverageSummary(actionCoveragePath)*/
            val ewtgFolderPath: Path = getEWTGFolderPath(modelPath)
            log.info("Loading EWTG")
            loadEWTG(ewtgFolderPath, autAutMF)
            val windowList = if (isSameVersion) {
                WindowManager.instance.updatedModelWindows
            } else {
                WindowManager.instance.baseModelWindows
            }
            /*windowList.forEach {
                if (!AbstractStateManager.INSTANCE.ABSTRACT_STATES.any {
                        it is VirtualAbstractState
                                && it.window == it
                    }) {
                    val virtualAbstractState = VirtualAbstractState(it.classType, it, it is Launcher)
                    AbstractStateManager.INSTANCE.ABSTRACT_STATES.add(virtualAbstractState)
                }
            }*/
            val dstgFolderPath: Path = getDSTGFolderPath(modelPath)
            log.info("Loading DSTG")
            loadDSTG(dstgFolderPath, autAutMF)
        }

        val guiActionIncreasingCoverage = HashMap<Int, Int>()
        val guiActionCoverage = HashMap<Int, Int>()
        private fun loadActionCoverageSummary(actionCoveragePath: Path) {
            val lines: List<String>
            if (Files.exists(actionCoveragePath)) {
                actionCoveragePath.toFile().let { file ->
                    lines = BufferedReader(FileReader(file)).lines().skip(1).toList()
                }
                lines.forEach { line ->
                    val data = splitCSVLineToField(line)
                    val actionId = data[0].toInt()
                    val coverage = data[5].toInt()
                    val increasingCoverage = data[6].toInt()
                    guiActionIncreasingCoverage.put(actionId, increasingCoverage)
                    guiActionCoverage.put(actionId, coverage)
                }
            }
        }

        private fun loadEWTG(ewtgFolderPath: Path, autAutMF: org.atua.modelFeatures.ATUAMF) {
            val ewtgWindowsFilePath: Path = getEWTGWindowsFilePath(ewtgFolderPath)
            if (!Files.exists(ewtgFolderPath))
                return
            parseEWTGListFile(ewtgWindowsFilePath, autAutMF)
        }

        private fun parseEWTGListFile(ewtgWindowsFilePath: Path, autAutMF: org.atua.modelFeatures.ATUAMF) {
            val lines: List<String>
            ewtgWindowsFilePath.toFile().let { file ->
                lines = BufferedReader(FileReader(file)).use {
                    it.lines().skip(1).toList()
                }
            }

            lines.forEach { line ->
                loadWindow(line, ewtgWindowsFilePath.parent, autAutMF)
            }
        }

        private fun loadWindow(line: String, ewtgFolderPath: Path, autAutMF: org.atua.modelFeatures.ATUAMF) {
            val data = splitCSVLineToField(line)
            val windowId = data[0]
            val createdAtRuntime = data[4].toBoolean()
            if (createdAtRuntime) {
                createNewWindow(data)
            }
            val windowList = if (isSameVersion) {
                WindowManager.instance.updatedModelWindows
            } else {
                WindowManager.instance.baseModelWindows
            }
            var window = windowList.find { it.windowId == windowId }
            if (window == null) {
                window = createNewWindow(data)

            }
            loadWindowStructure(window, ewtgFolderPath)
            loadWindowEvents(window, ewtgFolderPath, autAutMF)
        }

        private fun loadWindowStructure(window: Window, ewtgFolderPath: Path) {
            val structureFilePath = ewtgFolderPath.resolve("WindowsWidget").resolve("Widgets_${window.windowId}.csv")
            if (!Files.exists(structureFilePath)) {
                org.atua.modelFeatures.ATUAMF.log.debug("Window $window 'structure does not exist")
                return
            }
            val lines: List<String>
            structureFilePath.toFile().let { file ->
                lines = BufferedReader(FileReader(file)).use {
                    it.lines().skip(1).toList()
                }
            }
            val widgetParentIdMap = HashMap<EWTGWidget, String>()
            lines.forEach { line ->
                val data = splitCSVLineToField(line)
                val widgetId = data[0]
                val widget = window.widgets.find { it.widgetId == widgetId }
                if (widget == null) {
                    //create new widget
                    createNewWidget(data, window, widgetParentIdMap)
                } else {
                    val parentId = data[3]
                    val structure = if (data[4] == "null") {
                        ""
                    } else {
                        data[4]
                    }
                    widget!!.structure = structure
                    if (parentId != "null") {
                        widgetParentIdMap.put(widget, parentId)
                    }
                }
            }
            window.widgets.forEach {
                val parentId = widgetParentIdMap[it]
                if (parentId != null) {
                    val parentWidget = window.widgets.find { it.widgetId == parentId }
                    if (parentWidget != null)
                        it.parent = parentWidget
                }
            }

        }

        private fun loadWindowEvents(window: Window, ewtgFolderPath: Path, autautMF: org.atua.modelFeatures.ATUAMF) {
            val eventFilePath = ewtgFolderPath.resolve("WindowsEvents").resolve("Events_${window.windowId}.csv")
            if (!Files.exists(eventFilePath)) {
                org.atua.modelFeatures.ATUAMF.log.debug("Window $window 'events does not exist")
                return
            }
            val lines: List<String>
            eventFilePath.toFile().let { file ->
                lines = BufferedReader(FileReader(file)).use {
                    it.lines().skip(1).toList()
                }
            }
            lines.forEach { line ->
                val data = splitCSVLineToField(line)
                val eventType = data[0]
                val widgetId = data[1]
                val createdAtRuntime = data[3].toBoolean()
                val widget = if (widgetId != "null") {
                    window.widgets.find { it.widgetId == widgetId }
                } else
                    null
                if (widgetId != "null" && widget == null)
                    log.warn("Cannot find widget $widgetId in the window $window ")
                if (widgetId == "null" || widget != null) {
                    val existingEvent = if (widgetId == "null")
                        window.inputs.find { it.eventType.toString() == eventType }
                    else {
                        window.inputs.find { it.eventType.toString() == eventType && it.widget == widget }
                    }
                    val event = if (existingEvent == null) {
                        createNewInput(data, widget, window, createdAtRuntime)
                    } else
                        existingEvent
                    if (event != null) {
                        event.exercisedInThePast = true
                        updateHandlerAndModifiedMethods(event, data, window, autautMF)
                        val isTargetInput = event?.modifiedMethods.isNotEmpty()
                        if (!ModelHistoryInformation.INSTANCE.inputUsefulness.containsKey(event)) {
                            val totalActionCnt = data[4].toInt()
                            val increasingActionCnt = data[5].toInt()
                            ModelHistoryInformation.INSTANCE.inputUsefulness.put(
                                event,
                                Pair(totalActionCnt, increasingActionCnt)
                            )
                        }
                        /*val isUseless = data[9]
                        if (isUseless!= "null") {
                            event.isUseless = isUseless.toBoolean()
                        }*/
                    }
                }
            }
        }

        private fun updateHandlerAndModifiedMethods(
            event: Input,
            data: List<String>,
            window: Window,
            autMF: org.atua.modelFeatures.ATUAMF
        ) {
            val eventHandlers = splitCSVLineToField(data[4])
            eventHandlers.filter { it.isNotBlank() }.forEach { handler ->
                val methodId = autMF.statementMF!!.getMethodId(handler)
                if (methodId.isNotBlank()) {
                    if (!event.eventHandlers.contains(methodId)) {
                        autMF.modifiedMethodWithTopCallers
                            .filter { it.value.contains(methodId) }
                            .forEach { updatedMethod, callers ->
                                if (!event.modifiedMethods.containsKey(updatedMethod)) {
                                    event.modifiedMethods.putIfAbsent(updatedMethod, false)
                                    val updatedStatements = autMF.statementMF!!.getMethodStatements(methodId)
                                    updatedStatements.forEach {
                                        event.modifiedMethodStatement.put(it, false)
                                    }
                                }
                            }
                        event.eventHandlers.add(methodId)
                    }
                }
            }
            if (data.size>6) {
                val coveredMethods = splitCSVLineToField(data[6])
                val coveredMethodsId = coveredMethods.map { autMF.statementMF!!.getMethodId(it) }
                coveredMethodsId.forEach { methodId ->
                    if (methodId.isNotBlank()) {
                        event.coveredMethods.put(methodId, false)
                        if (autMF.statementMF!!.isModifiedMethod(methodId)) {
                            event.modifiedMethods.putIfAbsent(methodId, false)
                        }
                        autMF.modifiedMethodWithTopCallers
                            .filter { it.value.contains(methodId) }
                            .forEach { updatedMethod, callers ->
                                if (!event.modifiedMethods.containsKey(updatedMethod)) {
                                    event.modifiedMethods.putIfAbsent(updatedMethod, false)
                                    val updatedStatements = autMF.statementMF!!.getMethodStatements(methodId)
                                    updatedStatements.forEach {
                                        event.modifiedMethodStatement.put(it, false)
                                    }
                                }
                            }
                    }
                }
            }
/*            val modifiedMethods = splitCSVLineToField(data[5])
modifiedMethods.filter { it.isNotBlank() }. forEach { method ->
    val methodId = autMF.statementMF!!.getMethodId(method)
    if (!event.modifiedMethods.contains(methodId)) {
        event.modifiedMethods.put(methodId,false)
        val updatedStatements = autMF.statementMF!!.getMethodStatements(methodId)
        updatedStatements.forEach {
            event.modifiedMethodStatement.put(it,false)
        }

    }
}*/
        }

        private fun loadDSTG(dstgFolderPath: Path, autAutMF: org.atua.modelFeatures.ATUAMF) {
            val abstractStateFilePath: Path = getAbstractStateListFilePath(dstgFolderPath)
            if (!abstractStateFilePath.toFile().exists())
                return
            loadAbstractStates(abstractStateFilePath, dstgFolderPath,autAutMF)
            val dstgFilePath: Path = getDSTGFilePath(dstgFolderPath)
            if (!dstgFilePath.toFile().exists())
                return
            loadDSTGFile(dstgFilePath, autAutMF)
            //Load abstraction functions
            val abstractionFunctionFolderPath: Path = getAbstractionFunctionFolderPath(dstgFolderPath)
            if (!abstractionFunctionFolderPath.toFile().exists())
                throw Exception("Cannot find AbstractionFunction")
            loadAbstractionFunction(abstractionFunctionFolderPath)
        }

        private fun loadAbstractionFunction(abstractionFunctionFolderPath: Path) {
            loadDecisionNodes(abstractionFunctionFolderPath)
            //loadAbandonedAttributeValuationSet(abstractionFunctionFolderPath)
        }

        private fun loadAbandonedAttributeValuationSet(abstractionFunctionFolderPath: Path) {
            val abandonedAVSFile = abstractionFunctionFolderPath.resolve("abandonedAttributeValuationSet.csv")
            val lines: List<String>
            lines = readAllLines(abandonedAVSFile)
            lines.forEach { line ->
//              parseAbandonedAttributeValuationSet(line)
            }
        }

        /*private fun parseAbandonedAttributeValuationSet(line: String): AttributeValuationMap {
            val data = splitCSVLineToField(line)
            val activity = data[0]
            val avsId = data[1]
            val avs = AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP.get(activity)!!.get(avsId)!!
            //AbstractionFunction.INSTANCE.abandonedAbstractTransitions.add(Triple(activity,avs,actionType))
            return avs
        }*/

        private fun loadDecisionNodes(abstractionFunctionFolderPath: Path) {
            var currentDecisionNode: DecisionNode2? = null
            var level = 0
            do {
                level++
                if (currentDecisionNode == null)
                    currentDecisionNode = AbstractionFunction2.INSTANCE.root
                else
                    currentDecisionNode = currentDecisionNode.nextNode
                val decisionNodeDataFilePath = abstractionFunctionFolderPath.resolve("DecisionNode_LV$level.csv")
                loadDecisionNode(decisionNodeDataFilePath, currentDecisionNode!!)
            } while (currentDecisionNode != null && currentDecisionNode.nextNode != null)

        }

        private fun loadDecisionNode(decisionNodeDataFilePath: Path, currentDecisionNode: DecisionNode2) {
            val lines: List<String>
            lines = readAllLines(decisionNodeDataFilePath)
            val parentAttributePaths = HashSet<Pair<UUID, String>>()
            lines.forEach {
                parseDecisionNode(it, currentDecisionNode, parentAttributePaths)
                //currentDecisionNode.attributePaths.add(Pair(attributePath,attributePath.activity))
            }
            /*parentAttributePaths.forEach {
                val attributePath = AttributePath.getAttributePathById(it.first,it.second)
                if (attributePath == null)
                    throw Exception()
            }*/
        }

        private fun parseDecisionNode(
            line: String,
            currentDecisionNode: DecisionNode2,
            parentAttributePaths: HashSet<Pair<UUID, String>>
        ) {
            val data = splitCSVLineToField(line)
            val widgetId = data[0]
            val ewtgWidget = EWTGWidget.allStaticWidgets.find { it.widgetId == widgetId }
            if (ewtgWidget != null) {
                currentDecisionNode.ewtgWidgets.add(ewtgWidget)
            }
            /*val activity = data[0]
            val attributPathUid = UUID.fromString(data[1])
            val parentId = if (data[2] == "null") {
                emptyUUID
            } else {
                UUID.fromString(data[2])
            }
            val attributes = HashMap<AttributeType,String>()
            var index = 3
            AttributeType.values().toSortedSet().forEach { attributeType ->
                val value = data[index]!!
                addAttributeIfNotNull(attributeType,value,attributes)
                index++
            }
            if (parentId != emptyUUID) {
                parentAttributePaths.add(Pair(parentId,activity))
            }
            val attributePath = AttributePath(
                   localAttributes = attributes,
                    parentAttributePathId = parentId,
                    activity = activity
            )
            //val child
            val captured = if (data[index]!!.isNotBlank()) {
                data[index].toBoolean()
            } else {
                false
            }
            if (captured) {
                if (!currentDecisionNode.attributePaths.containsKey(activity)) {
                    currentDecisionNode.attributePaths.put(activity, arrayListOf())
                }
                currentDecisionNode.attributePaths.get(activity)!!.add(attributePath)
            }
            assert(attributPathUid == attributePath.attributePathId)*/
        }

        private fun getAbstractionFunctionFolderPath(dstgFolderPath: Path): Path {
            return dstgFolderPath.resolve("AbstractionFunction")
        }

        private fun loadDSTGFile(dstgFilePath: Path, autAutMF: org.atua.modelFeatures.ATUAMF) {
            val lines: List<String>
            lines = readAllLines(dstgFilePath)
            var i = 0
            while (i < lines.size) {
                parseAbstractTransition(lines[i], autAutMF)
                i += 1
            }
        }

        private fun parseAbstractTransition(line: String, atuaMF: org.atua.modelFeatures.ATUAMF) {
            val data = splitCSVLineToField(line)
            if (data.size < 11)
                return
            val sourceStateId = data[0]

            val sourceState = if (updatedAbstractStateId.containsKey(sourceStateId)) {
                val newUUID = updatedAbstractStateId.get(sourceStateId)
                AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.abstractStateId == newUUID }
            } else {
                AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.abstractStateId == sourceStateId }
            }
            if (sourceState == null)
                return
            val destStateId = data[1]
            val destState = if (updatedAbstractStateId.containsKey(destStateId)) {
                val newUUID = updatedAbstractStateId.get(destStateId)
                AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.abstractStateId == newUUID }
            } else {
                AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.abstractStateId == destStateId }
            }
            if (destState == null)
                return
            val actionType = AbstractActionType.values().first { it.name == data[2] }
            if (actionType == AbstractActionType.LAUNCH_APP)
                return
            val interactedAVSId = if (data[3] == "null") {
                ""
            } else {
                val avmId = data[3]
                if (updatedAVMId.containsKey(avmId)) {
                    updatedAVMId[avmId]!!
                } else
                    avmId
            }
            val actionData = if (data[4] == "null") {
                null
            } else {
                data[4]
            }
            val interactionData = if (data[5] == "null") {
                null
            } else {
                data[5]
            }
            if (actionType == AbstractActionType.ACTION_QUEUE
                || actionType == AbstractActionType.UNKNOWN
                || actionType == AbstractActionType.RANDOM_KEYBOARD
            )
                return
            val abstractAction = createAbstractAction(actionType, interactedAVSId, actionData, sourceState)

            /*val prevWindowId = data[6]
            val prevWindow = WindowManager.instance.baseModelWindows.firstOrNull(){it.windowId == prevWindowId}?:WindowManager.instance.updatedModelWindows.firstOrNull(){it.windowId == prevWindowId}*/
            // val prevWindowAbstractStateId = data[6]
            // val prevWindowAbstractState = AbstractStateManager.instance.ABSTRACT_STATES.find { it.abstractStateId == prevWindowAbstractStateId }
            val guiTransitionIds = data[13]
            var isUsefullOnce = false
            if (guiTransitionIds.isBlank()) {
                return
            }
            /*val guiActionIds = splitCSVLineToField(guiTransitionIds)
            if (guiActionIds.map {
                    if (guiActionIncreasingCoverage.containsKey(it.toInt()))
                        guiActionIncreasingCoverage[it.toInt()]!!
                    else 0}.count { it > 0 }>1) {
                isUsefullOnce = false
            }*/
            var guardEnabled = data[6].toBoolean()
            val dependentAbstractStateIds = splitCSVLineToField(data[7])
            val dependentAbstractStates = ArrayList<AbstractState>()
            dependentAbstractStateIds.forEach { dependentAbstractStateId ->
                var dependentAbstractState: AbstractState? = null
                if (dependentAbstractStateId != "null") {
                    dependentAbstractState = if (updatedAbstractStateId.containsKey(dependentAbstractStateId)) {
                        val newUUID = updatedAbstractStateId.get(dependentAbstractStateId)
                        AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.abstractStateId == newUUID }
                    } else {
                        AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.abstractStateId == dependentAbstractStateId &&
                                (it !is VirtualAbstractState || it.window is Launcher)}
                    }
                }
                if (dependentAbstractState != null)
                    dependentAbstractStates.add(dependentAbstractState)
            }
            if (dependentAbstractStates.isNotEmpty()) {
                if (dependentAbstractStates.map { it.window }.contains(destState!!.window)) {
                    guardEnabled = true
                }
            }
            val handlers = splitCSVLineToField(data[8])
            val handlerIds = handlers.map { atuaMF.statementMF!!.getMethodId(it) }.filter { it != null }

            val userlikeInputs = data[9]
            val userlikeInputList = ArrayList<HashMap<UUID,String>>()
            if (userlikeInputs != "]" && userlikeInputs != "") {
                for (s in data[9].split("},")) {
                    val userlikeInputMap = HashMap<UUID, String>()
                    val dictStr = s.trim('[').trim(']').trim(',').trim('{').trim('}')
                    val dictElemStrs = dictStr.split("',")
                    dictElemStrs.forEach {
                        val dictElement = it.trim('\'')
                        val dictSplit = dictElement.split(":'")
                        val key = dictSplit[0].trim(':')
                        if (key.matches(Regex("[(0-9|(a-f)]+-[(0-9|(a-f)]+-[(0-9|(a-f)]+-[(0-9|(a-f)]+-[(0-9|(a-f)]+"))) {
                            if (dictSplit.size == 2) {
                                val v = dictSplit[1].trim('\'').replace("<semicolon>", ";")
                                    .replace("<newline>", "\\r\\n|\\r|\\n").replace("<comma>",",")
                                userlikeInputMap.put(UUID.fromString(key), v)
                            } else {
                                userlikeInputMap.put(UUID.fromString(key), "")
                            }
                        } else {
                            log.debug("$key does not match UUID")
                        }

                    }
                    if (userlikeInputMap.isNotEmpty()) {
                        userlikeInputList.add(userlikeInputMap)
                    }
                }
            }


            val coveredMethods = splitCSVLineToField(data[12])
            val coveredMethodIds = coveredMethods.map { atuaMF.statementMF!!.getMethodId(it) }.filter { it != null }
            val abstractTransition = sourceState.abstractTransitions.find {
                it.abstractAction == abstractAction
                        && it.isImplicit == false
                        && it.source == sourceState
                        && it.dest == destState
                        && it.dependentAbstractStates.intersect(dependentAbstractStates).isNotEmpty()
            }
            if (abstractTransition == null) {
                val newAbstractTransition = AbstractTransition(
                    source = sourceState,
                    dest = destState,
                    fromWTG = false,
                    isImplicit = false,
                    abstractAction = abstractAction,
                    modelVersion = ModelVersion.BASE
                )
                newAbstractTransition.isUsefullOnce = isUsefullOnce
                if (dependentAbstractStates.isNotEmpty()) {
                    newAbstractTransition.guardEnabled = guardEnabled
                    newAbstractTransition.dependentAbstractStates.addAll(dependentAbstractStates)
                }
                // atuaMF.dstg.updateAbstractActionEnability(newAbstractTransition,atuaMF)
                newAbstractTransition.computeGuaranteedAVMs()
                handlerIds.forEach {
                    newAbstractTransition.handlers.put(it, false)
                }

                newAbstractTransition.methodCoverage.addAll(coveredMethodIds)
                newAbstractTransition.methodCoverage.forEach {
                    if (atuaMF.statementMF!!.isModifiedMethod(it)) {
                        newAbstractTransition.modifiedMethods.put(it,false)
                    }
                }
                atuaMF.dstg.add(sourceState, destState, newAbstractTransition)
//                atuaMF.dstg.updateAbstractActionEnability(newAbstractTransition,atuaMF)
                createWindowTransitionFromAbstractInteraction(newAbstractTransition, atuaMF)
                /*AbstractStateManager.INSTANCE.addImplicitAbstractInteraction(
                        abstractTransition = newAbstractTransition,
                        currentState = null,
                        transitionId = null
                )*/
                newAbstractTransition.userInputs.addAll (userlikeInputList)
                newAbstractTransition.markNondeterministicTransitions(atuaMF)
            }
            else {
                abstractTransition.userInputs.addAll(userlikeInputList)
                abstractTransition.dependentAbstractStates.addAll(dependentAbstractStates)
            }

        }

        private fun createAbstractAction(
            actionType: AbstractActionType,
            interactedAVSId: String,
            actionData: String?,
            abstractState: AbstractState
        ): AbstractAction {
            if (interactedAVSId == "") {
                val abstractAction = AbstractAction.getOrCreateAbstractAction(
                    actionType = actionType,
                    attributeValuationMap = null,
                    extra = actionData,
                    window = abstractState.window
                )
                return abstractAction
            }
            val avs = abstractState.attributeValuationMaps.firstOrNull() { it.avmId == interactedAVSId }
            val abstractAction = AbstractAction.getOrCreateAbstractAction(
                actionType = actionType,
                attributeValuationMap = avs,
                extra = actionData,
                window = abstractState.window
            )
            return abstractAction
        }

        private fun createWindowTransitionFromAbstractInteraction(
            abstractTransition: AbstractTransition,
            atuaMF: org.atua.modelFeatures.ATUAMF
        ) {
            val eventType = Input.getEventTypeFromActionName(abstractTransition.abstractAction.actionType)
            if (eventType == EventType.fake_action || eventType == EventType.resetApp || eventType == EventType.implicit_launch_event)
                return
            val inputs =
                if (abstractTransition.source.isAbstractActionMappedWithInputs(abstractTransition.abstractAction))
                    abstractTransition.source.getInputsByAbstractAction(abstractTransition.abstractAction)
                else
                    createNewInput(abstractTransition, atuaMF)
            val prevWindows = abstractTransition.dependentAbstractStates.map { it.window }
            /*val guiActionsCnt = oldGuiActionIds.size
            val increasingCoverageCnt = oldGuiActionIds.map {
                if (guiActionIncreasingCoverage.containsKey(it))
                    guiActionIncreasingCoverage[it]!!
                else 0}.count { it > 0 }*/
            inputs.forEach { input ->
                /*ModelHistoryInformation.INSTANCE.inputUsefulness.putIfAbsent(input,Pair(0,0))
                val inputCoverageHistory = ModelHistoryInformation.INSTANCE.inputUsefulness.get(input)!!
                val newTotalActionsCnt = inputCoverageHistory.first + guiActionsCnt
                val newIncreasingCoverageCnt = inputCoverageHistory.second + increasingCoverageCnt
                ModelHistoryInformation.INSTANCE.inputUsefulness.put(input,Pair(newTotalActionsCnt,newIncreasingCoverageCnt))*/
                /*val inputCoveredMethods = input.coveredMethods.keys
                if (!input.usefullOnce && inputCoveredMethods.isNotEmpty() && inputCoveredMethods.intersect(abstractTransition.methodCoverage).size == input.coveredMethods.size) {
                    input.usefullOnce = true
                }*/
                input.coveredMethods.putAll(abstractTransition.methodCoverage.associateWith { false })
                input.coveredMethods.keys.forEach {
                    if (atuaMF.statementMF!!.isModifiedMethod(it)) {
                        input.modifiedMethods.put(it,false)
                    }
                }
                /*if (prevWindows.isEmpty())
                    atuaMF.wtg.add(
                        abstractTransition.source.window, abstractTransition.dest.window, WindowTransition(
                            abstractTransition.source.window,
                            abstractTransition.dest.window,
                            input,
                            null
                        )
                    )
                else
                    prevWindows.forEach { prevWindow ->
                        atuaMF.wtg.add(
                            abstractTransition.source.window, abstractTransition.dest.window, WindowTransition(
                                abstractTransition.source.window,
                                abstractTransition.dest.window,
                                input,
                                prevWindow
                            )
                        )

                    }*/
            }

        }

        private fun createNewInput(
            abstractTransition: AbstractTransition,
            atuaMF: org.atua.modelFeatures.ATUAMF
        ): HashSet<Input> {
            val result = HashSet<Input>()
            val eventType = Input.getEventTypeFromActionName(abstractTransition.abstractAction.actionType)
            val sourceAbstractState = abstractTransition.source
            val destAbstractState = abstractTransition.dest
            if (abstractTransition.abstractAction.attributeValuationMap == null) {
                var newInput =
                    Input.getOrCreateInput(
                        eventHandlers = emptySet(),
                        eventTypeString = eventType.toString(),
                        widget = null,
                        sourceWindow = sourceAbstractState.window,
                        createdAtRuntime = true,
                        modelVersion = ModelVersion.BASE
                    )
                if (newInput == null) {
                    return result
                }
                result.add(newInput)
                newInput.data = abstractTransition.abstractAction.extra
                newInput.eventHandlers.addAll(abstractTransition.handlers.map { it.key })
                sourceAbstractState.associateAbstractActionWithInputs(abstractTransition.abstractAction, newInput)
                AbstractStateManager.INSTANCE.ABSTRACT_STATES.filterNot { it == sourceAbstractState }
                    .filter { it.window == sourceAbstractState.window }.forEach {
                        val similarAbstractAction =
                            it.getAvailableActions().find { it == abstractTransition.abstractAction }
                        if (similarAbstractAction != null) {
                            it.associateAbstractActionWithInputs(similarAbstractAction, newInput)
                        }
                    }
            } else {
                val attributeValuationSet = abstractTransition.abstractAction.attributeValuationMap!!
                if (!sourceAbstractState.EWTGWidgetMapping.containsKey(attributeValuationSet)) {
                    val attributeValuationSetId = if (attributeValuationSet.getResourceId().isBlank())
                        ""
                    else
                        attributeValuationSet.avmId
                    // create new static widget and add to the abstract state
                    val ewtgWidget = EWTGWidget(
                        widgetId = attributeValuationSet.avmId.toString(),
                        resourceIdName = attributeValuationSet.getResourceId(),
                        window = sourceAbstractState.window,
                        className = attributeValuationSet.getClassName(),
                        contentDesc = attributeValuationSet.getContentDesc(),
                        text = attributeValuationSet.getText(),
                        createdAtRuntime = true,
                        structure = attributeValuationSetId
                    )
                    ewtgWidget.modelVersion = ModelVersion.BASE
                    sourceAbstractState.EWTGWidgetMapping.put(attributeValuationSet, ewtgWidget)
                    AbstractStateManager.INSTANCE.ABSTRACT_STATES.filterNot { it == sourceAbstractState }
                        .filter { it.window == sourceAbstractState.window }.forEach {
                            val similarWidget = it.attributeValuationMaps.find { it == attributeValuationSet }
                            if (similarWidget != null) {
                                it.EWTGWidgetMapping.put(similarWidget, ewtgWidget)
                            }
                        }
                }
                if (sourceAbstractState.EWTGWidgetMapping.contains(attributeValuationSet)) {
                    val staticWidget = sourceAbstractState.EWTGWidgetMapping[attributeValuationSet]!!
                    atuaMF.allTargetStaticWidgets.add(staticWidget)
                    val newInput = Input.getOrCreateInput(
                        eventHandlers = emptySet(),
                        eventTypeString = eventType.toString(),
                        widget = staticWidget,
                        sourceWindow = sourceAbstractState.window,
                        createdAtRuntime = true,
                        modelVersion = ModelVersion.BASE
                    )
                    if (newInput == null)
                        return result
                    result.add(newInput)
                    newInput.data = abstractTransition.abstractAction.extra
                    newInput.eventHandlers.addAll(abstractTransition.handlers.map { it.key })
                    sourceAbstractState.associateAbstractActionWithInputs(abstractTransition.abstractAction, newInput)
                    AbstractStateManager.INSTANCE.ABSTRACT_STATES.filterNot { it == sourceAbstractState }
                        .filter { it.window == sourceAbstractState.window }.forEach {
                            val similarAbstractAction =
                                it.getAvailableActions().find { it == abstractTransition.abstractAction }
                            if (similarAbstractAction != null) {
                                it.associateAbstractActionWithInputs(similarAbstractAction, newInput)
                            }
                        }
                }
            }
            return result
        }

        private fun readAllLines(dstgFilePath: Path): List<String> {
            val lines: List<String>
            dstgFilePath.toFile().let { file ->
                lines = BufferedReader(FileReader(file)).use {
                    it.lines().skip(1).toList()
                }
            }
            return lines
        }

        private fun loadAbstractStates(abstractStateFilePath: Path, dstgFolderPath: Path,atuaMF: ATUAMF) {
            val lines: List<String> = readAllLines(abstractStateFilePath)

            lines.forEach { line ->
                loadAbstractState(line, dstgFolderPath,atuaMF)
            }
        }

        val updatedAbstractStateId = HashMap<String, String>()
        val updatedAVMId = HashMap<String, String>()

        private fun loadAbstractState(line: String, dstgFolderPath: Path,atuaMF: ATUAMF) {
            val data = splitCSVLineToField(line)
            val uuid = data[0]
            val activity = data[1]
            val windowId = data[2]
            val windowList = if (isSameVersion) {
                WindowManager.instance.updatedModelWindows
            } else {
                WindowManager.instance.baseModelWindows
            }
            val window = windowList.find { it.windowId == windowId }
            if (window == null) {
                log.debug("Cannot find window $windowId")
                return
            }
            val rotation = org.atua.modelFeatures.Rotation.values().find { it.name == data[3] }!!
            val isMenuOpen = data[4].toBoolean()
            val isHomeScreen = data[5].toBoolean()
            val isRequestRuntimePermissionDialogBox = data[6].toBoolean()
            val isAppHasStoppedDialogBox = data[7].toBoolean()
            val isOutOfApp = data[8].toBoolean()
            val isOpenningKeyboard = data[9].toBoolean()
            val hasOptionsMenu = data[10].toBoolean()
            val guiStates = data[11]
            val hashcode = data[12].toInt()
            val isInitalState = data[13].toBoolean()
            val widgetIdMapping: HashMap<AttributeValuationMap, String> = HashMap()
            val avmCardinalities = HashMap<AttributeValuationMap, Cardinality>()
            val attributeValuationSets =
                loadAttributeValuationSets(uuid, dstgFolderPath, widgetIdMapping, avmCardinalities, window,atuaMF)
            val widgetMapping = HashMap<AttributeValuationMap, EWTGWidget>()
            widgetIdMapping.forEach { avs, widgetId ->
                val widget = window.widgets.find { it.widgetId == widgetId }
                if (widget != null) {
                    widgetMapping.put(avs, widget)
                } else {
                    log.debug("Cannot find WidgetId $widgetId in $window")
                }
            }
            val abstractState = AbstractState(
                activity = activity,
                isOutOfApplication = isOutOfApp,
                isHomeScreen = isHomeScreen,
                rotation = rotation,
                isOpeningKeyboard = isOpenningKeyboard,
                isRequestRuntimePermissionDialogBox = isRequestRuntimePermissionDialogBox,
                isAppHasStoppedDialogBox = isAppHasStoppedDialogBox,
                attributeValuationMaps = ArrayList(attributeValuationSets),
                avmCardinalities = avmCardinalities,
                EWTGWidgetMapping = widgetMapping,
                isOpeningMenus = isMenuOpen,
                window = window,
                loadedFromModel = true,
                modelVersion = ModelVersion.BASE
            )
            abstractState.hasOptionsMenu = hasOptionsMenu
            abstractState.updateHashCode()
            assert(abstractState.hashCode == hashcode)
            if (abstractState.abstractStateId != uuid) {
                updatedAbstractStateId.put(uuid, abstractState.abstractStateId)
            }
            AbstractStateManager.INSTANCE.ABSTRACT_STATES.add(abstractState)
            AbstractStateManager.INSTANCE.initAbstractInteractions(abstractState, null)
            if (isInitalState) {
                abstractState.isInitalState = true
            }
            if (window is Dialog) {
                val activity = windowList.find { it.classType == abstractState.activity }
                if (activity != null) {
                    window.ownerActivitys.add(activity)
                }
            }
        }


        private fun loadAttributeValuationSets(
            uuid: String, dstgFolderPath: Path,
            widgetMapping: HashMap<AttributeValuationMap, String>,
            avmCardinaties: HashMap<AttributeValuationMap, Cardinality>,
            window: Window,
            atuaMF: ATUAMF
        ): List<AttributeValuationMap> {
            val capturedAttributeValuationSets = ArrayList<AttributeValuationMap>()
            val abstractStateFilePath = dstgFolderPath.resolve("AbstractStates").resolve("AbstractState_$uuid.csv")
            if (!Files.exists(abstractStateFilePath)) {
                throw Exception("Cannot find the AbstractState $uuid")
            }
            val lines: List<String>
            abstractStateFilePath.toFile().let { file ->
                lines = BufferedReader(FileReader(file)).use {
                    it.lines().skip(1).toList()
                }
            }
            val attributeValuationSetRawData = HashMap<String, List<String>>()
            lines.forEach { line ->
                val rawData = splitCSVLineToField(line)
                val avsId = rawData[0]
                attributeValuationSetRawData.put(avsId, rawData)
            }
            for (attributeValuationSetRecord in attributeValuationSetRawData) {
                val avmuuid = attributeValuationSetRecord.key
                if (capturedAttributeValuationSets.any { it.avmId == avmuuid }) {
                    continue
                }
                val attributeValuationMap: AttributeValuationMap
                val existingAVM =
                    AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP.get(window)?.values?.find { it.avmId == avmuuid }
                if (existingAVM != null) {
                    attributeValuationMap = existingAVM
                } else {
                    attributeValuationMap = createAttributeValuationMap(
                        attributeValuationSetRecord.value, capturedAttributeValuationSets,
                        window, widgetMapping, avmCardinaties,atuaMF
                    )
                    if (attributeValuationMap.avmId != avmuuid)
                        updatedAVMId.put(avmuuid, attributeValuationMap.avmId)
                }
                val cardinality = Cardinality.values()
                    .find { it.name == attributeValuationSetRecord.value[AttributeValuationMapPropertyIndex.cardinality] }!!
                val captured = attributeValuationSetRecord.value[AttributeValuationMapPropertyIndex.captured]
                val ewtgWidgetIds =
                    splitCSVLineToField(attributeValuationSetRecord.value[AttributeValuationMapPropertyIndex.wtgWidgetMapping])
                if (captured.toBoolean()) {
                    capturedAttributeValuationSets.add(attributeValuationMap)
                    avmCardinaties.put(attributeValuationMap, cardinality)
                }
                if (!(ewtgWidgetIds.size == 1 &&
                            (ewtgWidgetIds.single().isBlank() || ewtgWidgetIds.single() == "null"))
                ) {
                    widgetMapping.put(attributeValuationMap, ewtgWidgetIds.first())
                }
            }
            /*AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP.get(window)?.values?.forEach {
                it.computeHashCode()
            }*/
            return capturedAttributeValuationSets
        }

        private fun createAttributeValuationMap(
            attributeValuationSetRawRecord: List<String>,
            attributeValuationMaps: ArrayList<AttributeValuationMap>,
            window: Window,
            widgetMapping: HashMap<AttributeValuationMap, String>,
            avmCardinaties: HashMap<AttributeValuationMap, Cardinality>,
            atuaMF: ATUAMF
        ): AttributeValuationMap {
            //TODO("Not implemented")
            val parentAVSId =
                if (attributeValuationSetRawRecord[AttributeValuationMapPropertyIndex.parentAVMID] != "null")
                    attributeValuationSetRawRecord[AttributeValuationMapPropertyIndex.parentAVMID]
                else
                    ""
            var index = AttributeValuationMapPropertyIndex.startAttributeValue
            val attributes = HashMap<AttributeType, String>()
            AttributeType.values().toSortedSet().forEach { attributeType ->
                val value = attributeValuationSetRawRecord[index]!!
                addAttributeIfNotNull(attributeType, value, attributes)
                index++
            }


            var attributeValuationMap = AttributeValuationMap(
                avmId = attributeValuationSetRawRecord[AttributeValuationMapPropertyIndex.AttributeValuationSetID],
                localAttributes = attributes,
                parentAVMId = parentAVSId,
                window = window,
                atuaMF = atuaMF
            )
            val existingAVM = AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP[window]!!
                .values.find { it != attributeValuationMap && it.hashCode == attributeValuationMap.hashCode }
            if (existingAVM != null) {
                AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP[window]!!.remove(attributeValuationMap.avmId)
                attributeValuationMap = existingAVM
            } else {
                AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP[window]!!.put(
                    attributeValuationMap.avmId,
                    attributeValuationMap
                )
            }

            val hashcode = (attributeValuationSetRawRecord[index + 3]).toInt()
            assert(hashcode == attributeValuationMap.hashCode)
            return attributeValuationMap
        }

        private fun parseAttributes(attributeValuationSetRawDatum: List<String>): HashMap<AttributeType, String> {
            val attributes = HashMap<AttributeType, String>()

            val className = attributeValuationSetRawDatum[1]
            val resourceId = attributeValuationSetRawDatum[2]
            val contentDesc = attributeValuationSetRawDatum[3]
            val text = attributeValuationSetRawDatum[4] //TODO consider "null" text and null value
            val enabled = attributeValuationSetRawDatum[5]
            val selected = attributeValuationSetRawDatum[6]
            val checkable = attributeValuationSetRawDatum[7]
            val isInputField = attributeValuationSetRawDatum[8]
            val clickable = attributeValuationSetRawDatum[9]
            val longClickable = attributeValuationSetRawDatum[10]
            val scrollable = attributeValuationSetRawDatum[11]
            val scrollDirection = attributeValuationSetRawDatum[12]
            val checked = attributeValuationSetRawDatum[13]
            val isLeaf = attributeValuationSetRawDatum[14]
            val childrenStructure = attributeValuationSetRawDatum[15]
            val childrenText = attributeValuationSetRawDatum[16]
            val siblingInfo = attributeValuationSetRawDatum[17]

            addAttributeIfNotNull(AttributeType.className, className, attributes)
            addAttributeIfNotNull(AttributeType.resourceId, resourceId, attributes)
            addAttributeIfNotNull(AttributeType.contentDesc, contentDesc, attributes)
            addAttributeIfNotNull(AttributeType.text, text, attributes)
            addAttributeIfNotNull(AttributeType.enabled, enabled, attributes)
            addAttributeIfNotNull(AttributeType.selected, selected, attributes)
            addAttributeIfNotNull(AttributeType.checkable, checkable, attributes)
            addAttributeIfNotNull(AttributeType.isInputField, isInputField, attributes)
            addAttributeIfNotNull(AttributeType.clickable, clickable, attributes)
            addAttributeIfNotNull(AttributeType.longClickable, longClickable, attributes)
            addAttributeIfNotNull(AttributeType.scrollable, scrollable, attributes)

            addAttributeIfNotNull(AttributeType.checked, checked, attributes)
            addAttributeIfNotNull(AttributeType.isLeaf, isLeaf, attributes)
            addAttributeIfNotNull(AttributeType.childrenStructure, childrenStructure, attributes)
            addAttributeIfNotNull(AttributeType.childrenText, childrenText, attributes)
            addAttributeIfNotNull(AttributeType.siblingsInfo, siblingInfo, attributes)

            return attributes
        }

        private fun addAttributeIfNotNull(
            attributeType: AttributeType,
            attributeValue: String,
            attributes: HashMap<AttributeType, String>
        ) {
            if (attributeValue != "null") {
                if (attributeType == AttributeType.scrollDirection) {
                    if (attributeValue.toIntOrNull() == null) {
                        // make it compatible with old data
                        val scrollDirectionInt =
                            when (attributeValue) {
                                "HORIZONTAL" -> ScrollDirection.LEFT.flagValue or ScrollDirection.RIGHT.flagValue
                                "VERTICAL" -> ScrollDirection.UP.flagValue or ScrollDirection.DOWN.flagValue
                                "UP" -> ScrollDirection.UP.flagValue
                                "DOWN" -> ScrollDirection.DOWN.flagValue
                                "LEFT" -> ScrollDirection.LEFT.flagValue
                                "RIGHT" -> ScrollDirection.RIGHT.flagValue
                                "UNKNOWN" -> ScrollDirection.LEFT.flagValue or ScrollDirection.RIGHT.flagValue or ScrollDirection.UP.flagValue or ScrollDirection.DOWN.flagValue
                                else -> 0
                            }
                        attributes.put(attributeType, scrollDirectionInt.toString())
                    } else {
                        attributes.put(attributeType, attributeValue)
                    }

                } else
                    attributes.put(attributeType, attributeValue)
            }
        }


        private fun getDSTGFilePath(dstgFolderPath: Path): Path {
            return dstgFolderPath.resolve("DSTG.csv")
        }

        private fun getAbstractStateListFilePath(dstgFolderPath: Path): Path {
            return dstgFolderPath.resolve("AbstractStateList.csv")
        }

        private fun getDSTGFolderPath(modelPath: Path): Path {
            return modelPath.resolve("DSTG")
        }


        private fun createNewInput(
            data: List<String>,
            widget: EWTGWidget?,
            window: Window,
            createdAtRuntime: Boolean
        ): Input? {
            val eventTypeString = when (data[0]) {
                "touch" -> "click"
                "implicit_menu" -> "press_menu"
                "implicit_back_event" -> "press_back"
                else -> data[0]
            }
            val eventType = EventType.values().find { it.name == eventTypeString }
            if (eventType == null) {
                return null
            }
            val input = Input.getOrCreateInput(
                eventHandlers = HashSet(),
                eventTypeString = eventType.toString(),
                widget = widget,
                sourceWindow = window,
                createdAtRuntime = createdAtRuntime,
                modelVersion = ModelVersion.BASE
            )
            return input

        }


        fun splitCSVLineToField(line: String): List<String> {
            val data = ArrayList(line.split(";"))
            val result = ArrayList<String>()
            var startQuote = false
            var temp = ""
            for (s in data) {
                if (!startQuote) {
                    if (!s.startsWith("\"")) {
                        result.add(s)
                    } else {
                        if (s.length > 1 && s.endsWith('"') && s.startsWith('"')) {
                            result.add(s.trim('"'))
                        } else if (s.count { '"' == it } % 2 == 0) {
                            result.add(s)
                        } else {
                            startQuote = true
                            temp = s
                        }
                    }
                } else {
                    temp = temp + ";" + s
                    if (s.endsWith("\"")) {
                        startQuote = false
                        // remove quote
                        temp = temp.substring(1, temp.length - 1)
                        result.add(temp)
                        temp = ""
                    }
                }
            }
            return result
        }

        private fun createNewWidget(
            data: List<String>,
            window: Window,
            widgetParentIdMap: HashMap<EWTGWidget, String>
        ): EWTGWidget {
            val widgetId = data[0]
            val resourceIdName = data[1]
            val className = data[2]
            val parentId = data[3]
            val structure = if (data[4] == "null") {
                ""
            } else {
                data[4]
            }
            val activity = data[5]
            val createdAtRuntime = data[6].toBoolean()
            val widget = EWTGWidget(
                widgetId = widgetId,
                createdAtRuntime = createdAtRuntime,
                resourceIdName = resourceIdName,
                className = className,
                window = window,
                contentDesc = "",
                text = "",
                structure = structure
            )
            widget.modelVersion = ModelVersion.BASE
            if (parentId != "null") {
                widgetParentIdMap.put(widget, parentId)
            }
            return widget
        }

        private fun createNewWindow(data: List<String>): Window {
            val windowId = data[0]
            val windowType = data[1]
            val classType = data[2]
            val createdAtRuntime = data[3].toBoolean()
            val portraitDimension: Rectangle = Helper.parseRectangle(data[4])
            val landscapeDimension: Rectangle = Helper.parseRectangle(data[5])
            val portraitKeyboardDimension: Rectangle = Helper.parseRectangle(data[6])
            val landscapeKeyboardDimension: Rectangle = Helper.parseRectangle(data[7])
            val window = when (windowType) {
                "Activity" -> Activity.getOrCreateNode(
                    nodeId = windowId,
                    classType = classType,
                    runtimeCreated = createdAtRuntime,
                    isBaseMode = !isSameVersion
                )
                "Dialog" -> Dialog.getOrCreateNode(
                    nodeId = windowId,
                    classType = classType,
                    runtimeCreated = createdAtRuntime,
                    allocMethod = "",
                    isBaseModel = !isSameVersion
                )
                "OptionsMenu" -> OptionsMenu.getOrCreateNode(
                    nodeId = windowId,
                    classType = classType,
                    runtimeCreated = createdAtRuntime,
                    isBaseModel = !isSameVersion
                )
                "ContextMenu" -> ContextMenu.getOrCreateNode(
                    nodeId = windowId,
                    classType = classType,
                    runtimeCreated = createdAtRuntime,
                    isBaseModel = !isSameVersion
                )
                "OutOfApp" -> OutOfApp.getOrCreateNode(
                    nodeId = windowId,
                    activity = classType,
                    isBaseModel = !isSameVersion
                )
                "FakeWindow" -> FakeWindow.getOrCreateNode( isBaseModel = !isSameVersion)
                "Launcher" -> Launcher.getOrCreateNode()
                else -> throw Exception("Error windowType: $windowType")
            }
            window.portraitDimension = portraitDimension
            window.landscapeDimension = landscapeDimension
            window.portraitKeyboardDimension = portraitKeyboardDimension
            window.landscapeKeyboardDimension = landscapeKeyboardDimension
            return window
        }

        private fun getEWTGWindowsFilePath(ewtgFolderPath: Path): Path {
            return ewtgFolderPath.resolve("EWTG_WindowList.csv")
        }

        private fun getEWTGFolderPath(modelPath: Path): Path {
            return modelPath.resolve("EWTG")
        }
    }
}

class AttributeValuationMapPropertyIndex {
    companion object {
        val AttributeValuationSetID = 0
        val parentAVMID = 1
        val startAttributeValue = 2
        val xpath = 2
        val resourceId = 3
        val className = 4
        val contentDesc = 5
        val text = 6
        val checkable = 7
        val enabled = 8
        val password = 9
        val selected = 10
        val isInputField = 11
        val clickable = 12
        val longClickable = 13
        val scrollable = 14
        val scrollDirection = 15
        val checked = 16
        val isLeaf = 17
        val childrenStructure = 18
        val childrenText = 19
        val siblingsInfo = 20
        val cardinality = 21
        val captured = 22
        val wtgWidgetMapping = 23
        val hashcode = 24
    }
}