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

package org.atua.modelFeatures.dstg.reducer

import org.atua.modelFeatures.dstg.AbstractStateManager
import org.droidmate.deviceInterface.exploration.Rectangle
import org.atua.modelFeatures.dstg.AttributePath
import org.atua.modelFeatures.dstg.AttributeValuationMap
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.window.Window
import org.droidmate.explorationModel.emptyUUID
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class StateReducer
{
    companion object{
        fun reduce(guiState: State<*>, window: Window,
                   rotation: org.atua.modelFeatures.Rotation,
                   guiTreeRectangle: Rectangle,
                   isOptionsMenu : Boolean,
                   atuaMF: org.atua.modelFeatures.ATUAMF
        ): HashMap<Widget, AttributeValuationMap>{
            AttributeValuationMap.allWidgetAVMHashMap.putIfAbsent(window, HashMap())
            AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP.putIfAbsent(window,HashMap())
            AttributeValuationMap.attributePath_AttributeValuationMap.putIfAbsent(window, HashMap())
            val derivedWidgets = AttributeValuationMap.allWidgetAVMHashMap[window]!!
            val widgetAVMHashMap = HashMap<Widget, AttributeValuationMap>()
            val widgetReduceMap = HashMap<Widget,AttributePath>()
            val derivedAttributePaths = HashSet<AttributePath>()
            val capturedAttributePaths = ArrayList<AttributePath>()
            val tempFullAttrPaths = HashMap<Widget,AttributePath>()
            val tempRelativeAttrPaths = HashMap<Widget,AttributePath>()

            val visibleWidgets = Helper.getVisibleWidgets(guiState)
            val visibleWidgetsForAbstraction = Helper.getVisibleWidgetsForAbstraction(guiState)
            val capturedWidgets = if (window.classType.startsWith("com.oath.mobile.platform.phoenix.core.")) {
                visibleWidgets.filter { !it.isKeyboard }
            } else {
                visibleWidgetsForAbstraction
            }
         /*   capturedWidgets.forEach { widget ->
                if (derivedWidgets.containsKey(widget)) {
                    widgetAVMHashMap.put(widget,derivedWidgets.get(widget)!!)
                }
            }*/
            val toReduceWidgets = ArrayList(guiState.widgets.filter { !it.isKeyboard })
            toReduceWidgets.forEach {
                val existingAVM = derivedWidgets.get(it)
                if (existingAVM!=null) {
                    if (capturedWidgets.contains(it)) {
                       widgetAVMHashMap.put(it,existingAVM)
                    }
                }
            }
            toReduceWidgets.removeIf { derivedWidgets.containsKey(it) }
            //toReduceWidgets.removeIf { derivedWidgets.contains(it) }
            //val toReduceWidgets = Helper.getVisibleWidgetsForAbstraction(guiState)
            toReduceWidgets .forEach {
                val widgetAttributePath = if (tempFullAttrPaths.containsKey(it))
                {
                    tempFullAttrPaths[it]!!
                }
                else {
                    WidgetReducer.reduce(it, guiState,isOptionsMenu,guiTreeRectangle,window , rotation, atuaMF, tempFullAttrPaths, tempRelativeAttrPaths)
                }
                widgetReduceMap.put(it, widgetAttributePath)
                if (capturedWidgets.contains(it)) {
                    if (!capturedAttributePaths.contains(widgetAttributePath)) {
                        capturedAttributePaths.add(widgetAttributePath)
                    }
                }
                derivedAttributePaths.add(widgetAttributePath)
            }
            val workingList = Stack<AttributePath>()
            val processedList = Stack<AttributePath>()

            derivedAttributePaths.filter { it.parentAttributePathId == emptyUUID }.also {
                workingList.addAll(it)
            }

            while (workingList.isNotEmpty()) {
                val element = workingList.pop()
                processedList.add(element)
                var avm =  AttributeValuationMap.getExistingObject(element,window)
                if (avm == null)
                    avm =  AttributeValuationMap(element,window,atuaMF)
                /*val similarAVMs = AttributeValuationMap.ALL_ATTRIBUTE_VALUATION_MAP.get(window)!!.values.filter { it!=attributeValuationSet && it.haveTheSameAttributePath(attributeValuationSet) }
                if (similarAVMs.isNotEmpty()) {
                    throw Exception()
                }*/
                val relatedWidgets = widgetReduceMap.filter { it.value.equals(element) }
                relatedWidgets.forEach { w, _ ->
                    if (capturedWidgets.contains(w)) {
                        widgetAVMHashMap.put(w, avm)
                    }
                    derivedWidgets.put(w, avm)
                }
                val nonProcessedChildren =  derivedAttributePaths.filter { it.parentAttributePathId == element.attributePathId }.filterNot { processedList.contains(it) }
                nonProcessedChildren.forEach {
                    workingList.push(it)
                }
            }
            val widgetToAVMs =  guiState.widgets.filter { !it.isKeyboard }.associateWith { derivedWidgets.get(it) }
            if (widgetToAVMs.any{it.value == null}) {
                LoggerFactory.getLogger(this::class.java).warn("Some widgets have not been derived")
            }
            return widgetAVMHashMap
        }
    }

}