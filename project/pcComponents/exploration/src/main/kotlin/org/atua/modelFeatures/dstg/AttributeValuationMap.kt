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

package org.atua.modelFeatures.dstg

import org.calm.AppModelLoader
import org.calm.StringComparison
import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.ewtg.ScrollDirection
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.strategy.similarScore
import org.droidmate.explorationModel.emptyUUID
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.droidmate.explorationModel.sanitize
import org.droidmate.explorationModel.toUUID
import java.io.BufferedWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class AttributeValuationMap {
    var avmId: String
    val localAttributes: HashMap<AttributeType, String> = HashMap()
    var parentAttributeValuationMapId: String = ""
    var exerciseCount: Int = 0
    val availableAbstractActions = HashSet<AbstractAction>()
//    private val actionCount = HashMap<AbstractAction, Int>()
    var captured = false
    var hashCode: Int = 0
    var timestamp: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    var fullAttributeValuationMap: String = ""

    constructor(avmId: String, localAttributes: Map<AttributeType,String>, parentAVMId: String, window: Window,atuaMF: ATUAMF) {
        this.avmId = avmId
        this.localAttributes.putAll(localAttributes)
        this.parentAttributeValuationMapId = parentAVMId
        if (!ALL_ATTRIBUTE_VALUATION_MAP.containsKey(window)) {
            ALL_ATTRIBUTE_VALUATION_MAP.put(window, HashMap())
        }
        ALL_ATTRIBUTE_VALUATION_MAP[window]!!.put(avmId,this)
        hashCode = this.fullAttributeValuationMap(window).hashCode()
        maxId++
        initActions(window,atuaMF)
    }

    constructor(attributePath: AttributePath, window: Window,atuaMF: ATUAMF) {
        if (!ALL_ATTRIBUTE_VALUATION_MAP.containsKey(window)) {
            ALL_ATTRIBUTE_VALUATION_MAP.put(window, HashMap())
        }
        attributePath_AttributeValuationMap.putIfAbsent(window, HashMap())
        localAttributes.putAll(attributePath.localAttributes)
        var tempId = "${localAttributes.get(AttributeType.className)}_${localAttributes.get(AttributeType.resourceId)}_${maxId++}"
        while (true) {
            if (ALL_ATTRIBUTE_VALUATION_MAP[window]!!.containsKey(tempId)) {
                tempId = "${localAttributes.get(AttributeType.className)}_${localAttributes.get(AttributeType.resourceId)}_${maxId++}"
            } else {
                break
            }
        }
        avmId = tempId

        if (attributePath.parentAttributePathId == emptyUUID) {
            parentAttributeValuationMapId = ""
        } else {
            val parentAttributePath = AttributePath.getAttributePathById(attributePath.parentAttributePathId,window)
            if (parentAttributePath == null)
            {
                throw Exception()
            }
            val parentAVM = getExistingObject(parentAttributePath,window)
            if (parentAVM != null) {
                parentAttributeValuationMapId = parentAVM.avmId
            } else {
                val newParentAttributeValuationSet = AttributeValuationMap(parentAttributePath, window,atuaMF)
                parentAttributeValuationMapId = newParentAttributeValuationSet.avmId
            }
            /*if (ALL_ATTRIBUTE_VALUATION_MAP[window]!!.any { it.value.haveTheSameAttributePath(parentAttributePath) }) {
                parentAttributeValuationMapId = ALL_ATTRIBUTE_VALUATION_MAP[window]!!.map {it.value}.find{ it.haveTheSameAttributePath(parentAttributePath) }!!.avmId
            } else {
                var parentCardinality =  attributPath_cardinality.filterKeys { it.attributePathId == attributePath.parentAttributePathId }.values.firstOrNull()
                if (parentCardinality == null)
                    parentCardinality = Cardinality.ONE
                val newParentAttributeValuationSet = AttributeValuationMap(parentAttributePath,
                        parentCardinality, window,attributPath_cardinality)
                parentAttributeValuationMapId = newParentAttributeValuationSet.avmId
            }*/
        }
/*        if (attributePath.childAttributePathIds.isNotEmpty()) {
            attributePath.childAttributePathIds.map { AttributePath.getAttributePathById(it,activity) }.forEach {
                val childAttributeValuationSet = AttributeValuationMap(it,Cardinality.ONE,activity, HashMap())
                childAttributeValuationSetIds.add(childAttributeValuationSet.avsId)
            }
        }*/

        ALL_ATTRIBUTE_VALUATION_MAP[window]!!.put(avmId,this)
        attributePath_AttributeValuationMap[window]!!.put(attributePath,this)
        hashCode = this.fullAttributeValuationMap(window).hashCode()
        initActions(window,atuaMF)
    }

    fun computeHashCode(window: Window) {
        hashCode = this.fullAttributeValuationMap(window).hashCode()
    }

    fun isWebView(): Boolean {
        if (getClassName().equals("android.webkit.WebView")) {
            return true
        }
        return false
    }

    fun initActions(window: Window,atuaMF: ATUAMF) {
        /*if (!AbstractStateManager.instance.activity_attrValSetsMap.containsKey(activity))
            AbstractStateManager.instance.activity_attrValSetsMap.put(activity, ArrayList())*/
        if (isClickable() ) {
            if (getClassName().equals("android.webkit.WebView")) {
                val itemAbstractAction = AbstractAction.getOrCreateAbstractAction(
                        actionType = AbstractActionType.ITEM_CLICK,
                        attributeValuationMap = this,
                        window = window
                )

                addAndInitCount(itemAbstractAction, atuaMF)

                val itemLongClickAbstractAction = AbstractAction.getOrCreateAbstractAction(
                        actionType = AbstractActionType.ITEM_LONGCLICK,
                        attributeValuationMap = this,
                    window = window
                )
                addAndInitCount(itemLongClickAbstractAction, atuaMF)

            } else {
                val abstractAction = AbstractAction.getOrCreateAbstractAction(
                        actionType = AbstractActionType.CLICK,
                        attributeValuationMap = this,
                    window = window
                )
                addAndInitCount(abstractAction, atuaMF)

            }
        }

        if (isLongClickable() && !isInputField()) {
            if (getClassName().equals("android.webkit.WebView")) {
                val itemAbstractAction = AbstractAction.getOrCreateAbstractAction(
                    actionType = AbstractActionType.ITEM_LONGCLICK,
                    attributeValuationMap = this,
                    window = window
                )
                addAndInitCount(itemAbstractAction, atuaMF)

                val itemLongClickAbstractAction = AbstractAction.getOrCreateAbstractAction(
                    actionType = AbstractActionType.ITEM_LONGCLICK,
                    attributeValuationMap = this,
                    window = window
                )
                addAndInitCount(itemLongClickAbstractAction, atuaMF)

            } else {
                val abstractAction = AbstractAction.getOrCreateAbstractAction(
                    actionType = AbstractActionType.LONGCLICK,
                    attributeValuationMap = this,
                    window = window
                )
                addAndInitCount(abstractAction,atuaMF)

            }
        }

        if (isScrollable()) {
            if (isWebView()) {
                val abstractActionSwipeUp = AbstractAction.getOrCreateAbstractAction(
                    actionType = AbstractActionType.SWIPE,
                    attributeValuationMap = this,
                    extra = "SwipeUp",
                    window = window
                )
                val abstractActionSwipeDown = AbstractAction.getOrCreateAbstractAction(
                    actionType = AbstractActionType.SWIPE,
                    attributeValuationMap = this,
                    extra = "SwipeDown",
                    window = window
                )
                val abstractActionSwipeLeft = AbstractAction.getOrCreateAbstractAction(
                    actionType = AbstractActionType.SWIPE,
                    attributeValuationMap = this,
                    extra = "SwipeLeft",
                    window = window
                )
                val abstractActionSwipeRight = AbstractAction.getOrCreateAbstractAction(
                    actionType = AbstractActionType.SWIPE,
                    attributeValuationMap = this,
                    extra = "SwipeRight",
                    window = window
                )
                addAndInitCount(abstractActionSwipeUp,atuaMF)
                addAndInitCount(abstractActionSwipeDown,atuaMF)
                addAndInitCount(abstractActionSwipeLeft,atuaMF)
                addAndInitCount(abstractActionSwipeRight,atuaMF)

            }
            else {
                val scrollDirection = localAttributes[AttributeType.scrollDirection]!!.toInt()
                if (scrollDirection and ScrollDirection.LEFT.flagValue == ScrollDirection.LEFT.flagValue) {
                    val abstractActionSwipeLeft = AbstractAction.getOrCreateAbstractAction(
                        actionType = AbstractActionType.SWIPE,
                        attributeValuationMap = this,
                        extra = "SwipeLeft",
                        window = window
                    )
                    addAndInitCount(abstractActionSwipeLeft,atuaMF)
                }
                if (scrollDirection and ScrollDirection.RIGHT.flagValue == ScrollDirection.RIGHT.flagValue) {
                    val abstractActionSwipeRight = AbstractAction.getOrCreateAbstractAction(
                        actionType = AbstractActionType.SWIPE,
                        attributeValuationMap = this,
                        extra = "SwipeRight",
                        window = window
                    )
                    addAndInitCount(abstractActionSwipeRight,atuaMF)
                }
                if (scrollDirection and ScrollDirection.UP.flagValue == ScrollDirection.UP.flagValue) {
                    val abstractActionSwipeUp = AbstractAction.getOrCreateAbstractAction(
                        actionType = AbstractActionType.SWIPE,
                        attributeValuationMap = this,
                        extra = "SwipeUp",
                        window = window
                    )

                    addAndInitCount(abstractActionSwipeUp,atuaMF)

                }
                if (scrollDirection and ScrollDirection.DOWN.flagValue == ScrollDirection.DOWN.flagValue) {
                    val abstractActionSwipeDown = AbstractAction.getOrCreateAbstractAction(
                        actionType = AbstractActionType.SWIPE,
                        attributeValuationMap = this,
                        extra = "SwipeDown",
                        window = window
                    )
                    addAndInitCount(abstractActionSwipeDown,atuaMF)
                }

            }

            /*if (attributePath.getClassName().contains("RecyclerView")
                    || attributePath.getClassName().contains("ListView")
                    || attributePath.getClassName().equals("android.webkit.WebView") ) {
                val abstractActionSwipeTillEnd = AbstractAction(
                        actionType = AbstractActionType.SWIPE,
                        attributeValuationSet = this,
                        extra = "SwipeTillEnd"
                )
                actionCount.put(abstractActionSwipeTillEnd,0)
            }*/
        }
        if (isInputField()) {
            val abstractAction = AbstractAction.getOrCreateAbstractAction(
                    actionType = AbstractActionType.TEXT_INSERT,
                    attributeValuationMap = this,
                window = window
            )
            addAndInitCount(abstractAction,atuaMF)
        }
        //Item-containing Widget
        /* if (attributePath.getClassName().equals("android.webkit.WebView")) {
             val abstractAction = AbstractAction(
                     actionName = AbstractActionType.CLICK.actionName,
                     widgetGroup = this,
                     extra = "RandomMultiple"
             )
             actionCount.put(abstractAction, 0)
             *//*val longclickAbstractAction = AbstractAction(
                    actionName = AbstractActionType.LONGCLICK.actionName,
                    widgetGroup = this,
                    extra = "RandomMultiple"
            )
            actionCount.put(longclickAbstractAction, 0)*//*
        }*/
    }

    fun addAndInitCount(
        itemAbstractAction: AbstractAction,
        atuaMF: ATUAMF
    ) {
        if (!availableAbstractActions.contains(itemAbstractAction)) {
            availableAbstractActions.add(itemAbstractAction)
            atuaMF.actionCount.abstractActionCount.putIfAbsent(itemAbstractAction, 0)
        }
    }

    fun getAvailableActions(): List<AbstractAction> {
        return availableAbstractActions.toList()
    }

    fun getActionCount(abstractAction: AbstractAction,atuaMF: ATUAMF): Int {
        return atuaMF.actionCount.abstractActionCount[abstractAction]?:-1
    }

    fun removeAction(abstractAction: AbstractAction,atuaMF: ATUAMF) {
        availableAbstractActions.remove(abstractAction)
//        atuaMF.actionCount.abstractActionCount.remove(abstractAction)
    }

    fun setActionCount(abstractAction: AbstractAction, count: Int,atuaMF: ATUAMF) {
        addAndInitCount(abstractAction,atuaMF)
        atuaMF.actionCount.abstractActionCount[abstractAction] = count
    }

    fun getClassName(): String {
        if (localAttributes.containsKey(AttributeType.className))
        {
            return localAttributes[AttributeType.className]!!
        }
        return ""
    }
    fun getResourceId(): String {
        if (localAttributes.containsKey(AttributeType.resourceId))
        {
            return localAttributes[AttributeType.resourceId]!!
        }
        return ""
    }
    fun getContentDesc(): String {
        if (localAttributes.containsKey(AttributeType.contentDesc))
        {
            return localAttributes[AttributeType.contentDesc]!!
        }
        return ""
    }
    fun getText(): String {
        if (localAttributes.containsKey(AttributeType.text))
        {
            return localAttributes[AttributeType.text]!!
        }
        return ""
    }
    fun isClickable(): Boolean{
        if (localAttributes.containsKey(AttributeType.clickable))
        {
            return localAttributes[AttributeType.clickable]!!.toBoolean()
        }
        return false
    }
    fun isLongClickable(): Boolean{
        if (localAttributes.containsKey(AttributeType.longClickable))
        {
            return localAttributes[AttributeType.longClickable]!!.toBoolean()
        }
        return false
    }
    fun isScrollable(): Boolean{
        return localAttributes[AttributeType.scrollable]?.toBoolean()?:false
    }
    fun isInputField(): Boolean{
        if (localAttributes.containsKey(AttributeType.isInputField))
        {
            return localAttributes[AttributeType.isInputField]!!.toBoolean()
        }
        return false
    }

    fun isUserLikeInput(appState: AbstractState): Boolean {
        val widget = appState.EWTGWidgetMapping[this]
        if (widget != null && widget.verifiedNotUserlikeInput)
            return false
        val className = getClassName()
        return when (className) {
            "android.widget.RadioButton", "android.widget.CheckBox", "android.widget.Switch", "android.widget.ToggleButton", "android.widget.CheckedTextView" -> true
            else -> isInputField()
        }
    }

    fun isCheckable(): Boolean{
        if (localAttributes[AttributeType.checkable]?.equals("true")?:false)
        {
            return true
        }
        return false
    }
    fun isChecked(): Boolean {
        if (localAttributes.containsKey(AttributeType.checked))
        {
            return localAttributes[AttributeType.checked]!!.toBoolean()
        }
        return false
    }

    fun isEnable(): Boolean {
        if (localAttributes[AttributeType.enabled]!!.equals("true"))
        {
            return true
        }
        return false
    }
    fun isInteractive(): Boolean{
        return isClickable() || isLongClickable() || isScrollable() || isCheckable()
    }

    fun getGUIWidgets (guiState: State<*>,window: Window): List<Widget>{
        val selectedGuiWidgets = ArrayList<Widget>()
        Helper.getVisibleWidgetsForAbstraction(guiState).forEach {
            val attributeValuationMap = allWidgetAVMHashMap[window]!![it]
            if (attributeValuationMap != null) {
                if (attributeValuationMap == this || attributeValuationMap.hashCode == this.hashCode
                    || attributeValuationMap.isDerivedFrom(this, window))
                    selectedGuiWidgets.add(it)
                else if (isAbstractRepresentationOf(it,guiState,false)) {
                    selectedGuiWidgets.add(it)
                }
            }

        }
        /*if (selectedGuiWidgets.isEmpty()) {
            Helper.getVisibleWidgets(guiState).forEach {
                if (isAbstractRepresentationOf(it,guiState,true)) {
                    selectedGuiWidgets.add(it)
                }
            }
        }*/
        return selectedGuiWidgets
    }

    fun isAbstractRepresentationOf(widget: Widget, guiState: State<*>, compareDerived: Boolean): Boolean
    {
        val abstractState = AbstractStateManager.INSTANCE.getAbstractState(guiState)
        if (abstractState == null)
            return false
        val window = abstractState.window
        if (!allWidgetAVMHashMap.containsKey(window))
            return false
        val widget_AttributeValuationSet = allWidgetAVMHashMap[window]!!
        if (!widget_AttributeValuationSet.containsKey(widget))
            return false
        val derivedAttributeValuationSet = widget_AttributeValuationSet.get(widget)!!
        if (this.avmId == derivedAttributeValuationSet.avmId)
        {
            return true
        }
        if (compareDerived) {
            if (derivedAttributeValuationSet.isDerivedFrom(this,window)) {
                return true
            }
        }
        return false
    }

    fun havingSameContent(currentAbstractState: AbstractState, comparedAttributeValuationMap: AttributeValuationMap, comparedAbstractState: AbstractState): Boolean {
        val widgetGroupChildren = currentAbstractState.attributeValuationMaps.filter {
            isParent(it,currentAbstractState.window)
        }

        val comparedWidgetGroupChildren = comparedAbstractState.attributeValuationMaps.filter {
            comparedAttributeValuationMap.isParent(it,window = currentAbstractState.window)
        }
        widgetGroupChildren.forEach {w1 ->
            if (!comparedWidgetGroupChildren.any { w2 -> w1 == w2 }) {
                return false
            }
        }
        return true
    }

     fun isParent(attributeValuationMap: AttributeValuationMap, window: Window): Boolean {
        if (attributeValuationMap.parentAttributeValuationMapId== "")
            return false
         val parentAttributeValuationSet = ALL_ATTRIBUTE_VALUATION_MAP[window]!!.get(attributeValuationMap.parentAttributeValuationMapId)!!
        if (haveTheSameAttributePath(parentAttributeValuationSet,window))
            return true
        return false
    }

    fun haveTheSameAttributePath(attributePath: AttributePath, window: Window): Boolean {
        val fullAttributePath = attributePath.fullAttributePath()
        val fullAttributeValuationMap = this.fullAttributeValuationMap
        if (fullAttributePath.hashCode() == hashCode)
            return true
        return false
        if (localAttributes.hashCode() != attributePath.localAttributes.hashCode()) {
            return false
        }
        /*if (childAttributeValuationSetIds.isEmpty() && attributePath.childAttributePathIds.isNotEmpty()) {
            return false
        }
        if (childAttributeValuationSetIds.isNotEmpty() && attributePath.childAttributePathIds.isEmpty())
            return false
        val childAttributePaths = attributePath.childAttributePathIds.map { AttributePath.getAttributePathById(it,activity) }
        val childAttributeValuationSets = childAttributeValuationSetIds.map { ALL_ATTRIBUTE_VALUATION_MAP[activity]!!.get(it)!! }
        if (!childAttributeValuationSets.all { childAttributeValuationSet -> childAttributePaths.any { childAttributeValuationSet.haveTheSameAttributePath(it) } }) {
            return false
        }*/
        if (parentAttributeValuationMapId != "" && attributePath.parentAttributePathId == emptyUUID)
            return false
        if (parentAttributeValuationMapId == "" && attributePath.parentAttributePathId != emptyUUID)
            return false
        if (parentAttributeValuationMapId != "") {
            if (attributePath.parentAttributePathId == emptyUUID) {
                return false
            }
            val parentAttributeValuationSet = ALL_ATTRIBUTE_VALUATION_MAP[window]!!.get(parentAttributeValuationMapId)!!
            val parentAttributePath = AttributePath.getAttributePathById(attributePath.parentAttributePathId,window)
            if (parentAttributePath==null) {
                throw Exception()
            }
            if (!parentAttributeValuationSet.haveTheSameAttributePath(parentAttributePath,window)) {
                return false
            }
        }
        return true
    }

    fun haveTheSameAttributePath(cmpAttributeValuationMap: AttributeValuationMap, window: Window): Boolean {
        if (this.hashCode != cmpAttributeValuationMap.hashCode)
            return false
        return true
    }

    fun isDerivedFrom(abstractAttributeValuationMap: AttributeValuationMap, window: Window): Boolean {
        //check local
        abstractAttributeValuationMap.localAttributes.forEach {
            if (!localAttributes.containsKey(it.key))
                return false
            if (localAttributes[it.key] != it.value)
                return  false
        }
/*        if (childAttributeValuationSetIds.isEmpty() && !abstractAttributeValuationMap.childAttributeValuationSetIds.isEmpty()) {
            return false
        }
        if (!abstractAttributeValuationMap.childAttributeValuationSetIds.isEmpty()) {
            val childAttributeValuationSet = childAttributeValuationSetIds.map { ALL_ATTRIBUTE_VALUATION_MAP[activity]!!.get(it)!! }
            abstractAttributeValuationMap.childAttributeValuationSetIds.map { ALL_ATTRIBUTE_VALUATION_MAP[activity]!!.get(it)!! }. forEach { moreAbstractChildAVS ->
                if (!childAttributeValuationSet.any { it.isDerivedFrom(moreAbstractChildAVS) }) {
                    return false
                }
            }
        }*/
        if (parentAttributeValuationMapId!= "" && abstractAttributeValuationMap.parentAttributeValuationMapId!= "") {
            val parentAttributeValuationSet = ALL_ATTRIBUTE_VALUATION_MAP[window]!!.get(parentAttributeValuationMapId)
            if (parentAttributeValuationSet == null) {
                throw Exception("Cannot find attributeValuationSet $parentAttributeValuationMapId")
                return false
            }
            val abstractParentAttributeValuationSet = ALL_ATTRIBUTE_VALUATION_MAP[window]!!.get(abstractAttributeValuationMap.parentAttributeValuationMapId)
            if (abstractParentAttributeValuationSet == null) {
                //throw Exception("Cannot find attributeValuationSet $parentAttributeValuationMapId")
                return false
            }
            if (!parentAttributeValuationSet.isDerivedFrom(abstractParentAttributeValuationSet,window)) {
                return false
            }
        }
        return true
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("WidgetGroup[${getClassName()}]" +
                "[resourceId=${getResourceId()}]" +
                "[contentDesc=\"${getContentDesc().take(50)}]\"" +
                "[text=\"${getText().take(50).sanitize()}\"]" +
                "[clickable=${isClickable()}]" +
                "[longClickable=${isLongClickable()}]" +
                "[scrollable=${isScrollable()}]" +
                "[checkable=${isCheckable()}]")
        if (this.localAttributes.containsKey(AttributeType.childrenText)) {
            sb.append("[childrenText=\"${localAttributes[AttributeType.childrenText]!!.take(50).sanitize()}\"]")
        }
        if (this.localAttributes.containsKey(AttributeType.childrenStructure)) {
            sb.append("[childrenStructure=${localAttributes[AttributeType.childrenStructure]}]")
        }
        return sb.toString()
    }

    fun fullAttributeValuationMap(window: Window): String {
        val parentAttributeValuationMapString =  if (parentAttributeValuationMapId!="")
            ALL_ATTRIBUTE_VALUATION_MAP.get(window)!!.get(parentAttributeValuationMapId)!!.fullAttributeValuationMap(window)
        else
            ""
        val s =  listOf<String>(parentAttributeValuationMapString,localAttributes.toSortedMap().toString()).joinToString("<;>")
        fullAttributeValuationMap = s
        return s
    }
    /**
     * Write in csv
     */
    fun dump(abstractState: AbstractState): String {
        //testReloadAttributes()
        val dumpedString = listOf<String>(avmId,parentAttributeValuationMapId,dumpLocalAttributes()
                ,abstractState.avmCardinalities.get(this)?.toString()?:Cardinality.UNKNOWN.toString(),captured.toString(),
                abstractState.EWTGWidgetMapping[this]?.widgetId?:"",hashCode.toString()).joinToString(";")
        // loadDumpedString(dumpedString)
        return dumpedString
        /*return "${avmId};${getClassName()};${getResourceId()};" +
                "\"${localAttributes.get(AttributeType.contentDesc)}\";\"${localAttributes.get(AttributeType.text)}\";${isEnable()};" +
                "${localAttributes.get(AttributeType.selected)};${isCheckable()};${isInputField()};" +
                "${isClickable()};${isLongClickable()};${isScrollable()};" +
                "${localAttributes.get(AttributeType.checked)};${localAttributes.get(AttributeType.isLeaf)};" +
                "\"${localAttributes.get(AttributeType.childrenStructure)}\";\"${localAttributes.get(AttributeType.childrenText)}\";\"${localAttributes.get(AttributeType.siblingsInfo)}\";${dumpParentUUID()};$cardinality;$captured;" +
                "\"${abstractState.EWTGWidgetMapping[this]?.widgetId}\";$hashCode"*/
    }

    fun loadDumpedString(line: String,window: Window,atuaMF: ATUAMF) {
        val splites = AppModelLoader.splitCSVLineToField(line)
        val avmId = splites[0]
        val parentAVMId = splites[1]
        var index = 2
        val localAttributes = HashMap<AttributeType,String>()
        AttributeType.values().toSortedSet().forEach { attributeType ->
            val value = splites[index]!!
            addAttributeIfNotNull(attributeType,value,localAttributes)
            index++
        }
        val captured = splites[index+1]!!
        val hashcode = splites[index+3]!!.toInt()
        val newAttributeValuationMap = AttributeValuationMap(
                avmId = avmId,
                localAttributes = localAttributes,
                parentAVMId = parentAVMId,
                window = window,
            atuaMF = atuaMF
        )
        assert(hashcode==newAttributeValuationMap.hashCode)
    }

    private fun dumpLocalAttributes(): String {
        var result = ""
        var first = true
        AttributeType.values().toSortedSet().forEach { attributeType ->
            val value = localAttributes.get(attributeType)
            if (value==null) {
                result+= "null"
            } else {
                result += dumpAttributeValueToString(attributeType, value)
            }
            result+=";"
        }
        result = result.substring(0,result.length-1)
        return result
    }


    fun dumpAttributeValueToString(attributeType: AttributeType,value: Any?): String {
        return when (attributeType) {
            AttributeType.xpath -> value as String
            AttributeType.resourceId -> value as String
            AttributeType.className -> value as String
            AttributeType.contentDesc -> "\""+ (value as String?)?.replace("\n","\\n")+"\""
            AttributeType.text -> "\""+ (value as String?)?.replace("\n","\\n")+"\""
            AttributeType.checkable -> value.toString()
            AttributeType.enabled -> value.toString()
            AttributeType.password -> value.toString()
            AttributeType.selected -> value.toString()
            AttributeType.isInputField -> value.toString()
                //interactive
            AttributeType.clickable -> value.toString()
            AttributeType.longClickable -> value.toString()
            AttributeType.scrollable -> value.toString()
            AttributeType.scrollDirection -> value.toString()
            AttributeType.checked -> value.toString()
            AttributeType.isLeaf -> value.toString()
            AttributeType.childrenStructure -> "\""+ (value as String?)?.replace("\n","\\n")+"\""
            AttributeType.childrenText -> "\""+ (value as String?)?.replace("\n","\\n")+"\""
            AttributeType. siblingsInfo -> "\""+ (value as String?)?.replace("\n","\\n")+"\""
        }
    }

    private fun testReloadAttributes(window: Window) {
        val attributes = HashMap<AttributeType,String>()

        val className = getClassName()
        val resourceId = getResourceId()
        val contentDesc = "\""+ (localAttributes.get(AttributeType.contentDesc)?.replace("\n","\\n")?:"null")+ "\""
        val text = "\""+ (localAttributes.get(AttributeType.text)?.replace("\n","\\n")?:"null") + "\""
        val enabled = isEnable().toString()
        val selected = localAttributes.get(AttributeType.selected)?:"null".toString()
        val checkable = isCheckable().toString()
        val isInputField = isInputField().toString()
        val clickable = isClickable().toString()
        val longClickable = isLongClickable().toString()
        val scrollable = isScrollable().toString()
        val checked = localAttributes.get(AttributeType.checked)?:"null"
        val isLeaf = localAttributes.get(AttributeType.isLeaf)?:"null"

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

        val parentId = if (dumpParentUUID() !="null")
            dumpParentUUID()
        else
            ""
        val oldFullAttributeValuationSet = fullAttributeValuationMap(window)

        val oldUUID = oldFullAttributeValuationSet.toUUID()

    }
    private fun addAttributeIfNotNull(attributeType: AttributeType, attributeValue: String, attributes: HashMap<AttributeType,String>) {
        if (attributeValue!= "null" ) {
            val replaced = attributeValue.replace("\\n","\n")
            attributes.put(attributeType,replaced)
        }
    }
    private fun dumpParentUUID() = if (parentAttributeValuationMapId == "") "null" else parentAttributeValuationMapId

    fun dump(bufferedWriter: BufferedWriter, dumpedAttributeValuationSets: ArrayList<String>, abstractState: AbstractState ) {
        bufferedWriter.write(this.dump(abstractState))

        dumpedAttributeValuationSets.add(this.avmId)
        if (parentAttributeValuationMapId!= "") {
            if (!dumpedAttributeValuationSets.contains(parentAttributeValuationMapId)) {
                bufferedWriter.newLine()
                val parentAttributeValuationSet = ALL_ATTRIBUTE_VALUATION_MAP[abstractState.window]!!.get(parentAttributeValuationMapId)!!
                parentAttributeValuationSet.dump(bufferedWriter, dumpedAttributeValuationSets,abstractState)
            }
        }
/*        childAttributeValuationSetIds.forEach {
            if (!dumpedAttributeValuationSets.contains(it)) {
                val childAttributeValuationSet = ALL_ATTRIBUTE_VALUATION_MAP[activity]!!.get(it)!!
                bufferedWriter.newLine()
                childAttributeValuationSet.dump(bufferedWriter, dumpedAttributeValuationSets,abstractState)
            }
        }*/
    }

    fun getAvailableActionsWithExercisingCount(atuaMF: ATUAMF): Map<AbstractAction, Int> {
        return availableAbstractActions.associateWith { atuaMF.actionCount.abstractActionCount.get(it)!! }
    }

    fun containsAction(action: AbstractAction): Boolean {
        if (availableAbstractActions.contains(action))
            return true
        return false
    }

    fun hasSimilarText(avm: AttributeValuationMap): Boolean {
        var result = true
        this.localAttributes.forEach {
            if (it.key in arrayListOf<AttributeType>(AttributeType.childrenText,AttributeType.siblingsInfo,AttributeType.text)) {
                if (avm.localAttributes.containsKey(it.key)) {
                    val score = StringComparison.compareStringsLevenshtein(it.value,avm.localAttributes[it.key]!!)
                    if (score < 0.6) {
                        return false
                    }
                }
            }
        }
        return result
    }

    fun hasSimilarStructure(avm: AttributeValuationMap): Boolean {
        var result = true
        this.localAttributes.forEach {
            if (it.key in arrayListOf<AttributeType>(AttributeType.xpath,AttributeType.childrenStructure,AttributeType.isLeaf)) {
                if (avm.localAttributes.containsKey(it.key)) {
                    if (it.value != avm.localAttributes[it.key]) {
                        return false
                    }
                }
            }
        }
        return result
    }

    fun hasSimilarInteraction(avm: AttributeValuationMap): Boolean {
        var result = true
        this.localAttributes.forEach {
            if (it.key in arrayListOf<AttributeType>(AttributeType.checked,AttributeType.clickable,AttributeType.longClickable,AttributeType.scrollable,AttributeType.scrollDirection)) {
                if (avm.localAttributes.containsKey(it.key)) {
                    if (it.value != avm.localAttributes[it.key]) {
                        return false
                    }
                }
            }
        }
        return result
    }


    fun validateAction(abstractAction: AbstractAction): Boolean {
        val result = when(abstractAction.actionType) {
            AbstractActionType.SWIPE -> localAttributes[AttributeType.scrollable]!!.toBoolean() && when (abstractAction.extra) {
                "SwipeUp" -> (localAttributes[AttributeType.scrollDirection]?.toInt()?:0 and ScrollDirection.UP.flagValue) == ScrollDirection.UP.flagValue
                "SwipeDown" -> (localAttributes[AttributeType.scrollDirection]?.toInt()?:0 and ScrollDirection.DOWN.flagValue) == ScrollDirection.DOWN.flagValue
                "SwipeLeft" -> (localAttributes[AttributeType.scrollDirection]?.toInt()?:0 and ScrollDirection.LEFT.flagValue) == ScrollDirection.LEFT.flagValue
                "SwipeRight" -> (localAttributes[AttributeType.scrollDirection]?.toInt()?:0 and ScrollDirection.RIGHT.flagValue) == ScrollDirection.RIGHT.flagValue
                else -> true
            }
            else -> true
        }
        return result
    }
/*    fun increaseActionCount(action: AbstractAction):Int {
        if (actionCount.containsKey(action)) {
            val newValue = actionCount[action]!! + 1
            actionCount[action] = newValue
            return newValue
        }
        return -1
    }*/


    companion object {
        val ALL_ATTRIBUTE_VALUATION_MAP: HashMap<Window, HashMap<String,AttributeValuationMap>> = HashMap()
        val attributePath_AttributeValuationMap: HashMap<Window, HashMap<AttributePath,AttributeValuationMap>> = HashMap()
        val allWidgetAVMHashMap: HashMap<Window, HashMap<Widget,AttributeValuationMap>> = HashMap()
        var maxId: Long = 0
        fun getExistingObject(attributePath: AttributePath, window: Window): AttributeValuationMap? {
            var attributeValuationSet =  attributePath_AttributeValuationMap.get(window)!!.get(attributePath)
            if (attributeValuationSet == null) {
                // find the same AVM
                val avmsOfWindow = ALL_ATTRIBUTE_VALUATION_MAP.get(window)!!.values
                attributeValuationSet = avmsOfWindow.find { it.haveTheSameAttributePath(attributePath,window) }
            }
            return attributeValuationSet
        }
    }
}

enum class Cardinality{
    UNKNOWN,
    ONE,
    MANY
}