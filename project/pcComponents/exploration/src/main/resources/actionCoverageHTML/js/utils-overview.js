function overview() {
    var overviewtable = document.getElementById("overview-table")
    for (let actionId in data ) {
        var row = overviewtable.insertRow(-1)
        var actionIdCol = row.insertCell(0)
        actionIdCol.innerText = actionId
        var sourceStateImageCol = row.insertCell(1)
        var sourceImageSource = data[actionId].prevImage
        sourceStateImageCol.innerHTML = "<img class=\"img-fluid\" src=\""+sourceImageSource+ "\" id=\"prevImage\"/>"
        var actionTypeCol = row.insertCell(2)
        actionTypeCol.innerHTML = "<div>"+ data[actionId].actionType +"<div>"+ "<canvas id=\"widget_canvas"+actionId+"\" style=\"width:100%;height:auto\"></canvas>"
        redrawWidgetImage(actionId) 
        var desStateImageCol = row.insertCell(3)
        var desImageSource = data[actionId].image
        desStateImageCol.innerHTML = "<img class=\"img-fluid\" src=\""+desImageSource+ "\" id=\"prevImage\"/>"
        var coverageCol = row.insertCell(4)
        var coverageTable = document.createElement('table');
        coverageCol.appendChild(coverageTable)
        var targetStatementsCntRow = coverageTable.insertRow(-1)
        var labelCol = targetStatementsCntRow.insertCell(-1)
        labelCol.innerText = "Covered target statements count:"
        var valueCol = targetStatementsCntRow.insertCell(-1)
        valueCol.innerText = data[actionId].executedUpdatedStatements
        var newTargetStatementsCntRow = coverageTable.insertRow(-1)
        var labelCol = newTargetStatementsCntRow.insertCell(-1)
        labelCol.innerText = "New covered target statements count:"
        var valueCol = newTargetStatementsCntRow.insertCell(-1)
        valueCol.innerText = data[actionId].newExecutedUpdatedStatements
        var executedUpdatedMethodsRow = coverageTable.insertRow(-1)
        var methodsCol = executedUpdatedMethodsRow.insertCell(-1)
        var methods = "<ul>"
        for (let i=0; i < data[actionId].executedUpdatedMethods.length; i++) {
            var method = "<li>"
            method += escapeHtml(data[actionId].executedUpdatedMethods[i])
            method += "</li>"
            methods += method
        }
        methods += "</ul>"
        methodsCol.innerHTML = "<p>Covered target methods:</p>" + methods
    }
}

function drawWidget(actionData, canvasId) {
    var boundsX = actionData.targetWidget.visibleBounds.leftX;
    var boundsY = actionData.targetWidget.visibleBounds.topY;
    var boundsWidth = actionData.targetWidget.visibleBounds.width;
    var boundsHeight = actionData.targetWidget.visibleBounds.height;

    // https://stackoverflow.com/questions/53810434/crop-the-image-using-javascript
    var imageUrl = actionData.prevImage;
    const image = new Image();
    image.onload = function () {
        const canvas = document.getElementById(canvasId)

        // draw our image at position 0, 0 on the canvas
        const ctx = canvas.getContext("2d");
        if (boundsWidth < canvas.width) {
            var destWidth = boundsWidth
            var destHeight = boundsHeight
        } else {
            var destWidth = canvas.width;
            var destHeight = canvas.height;
        }
        if (boundsWidth > boundsHeight) {
            var scaleRatio = boundsWidth/destWidth;
            destHeight = boundsHeight/scaleRatio;
        }
        canvas.width = destWidth + 20
        canvas.height = destHeight + 20
        ctx.drawImage(image, boundsX, boundsY, boundsWidth, boundsHeight, 10, 10, destWidth, destHeight);
        // ctx.drawImage(image, 10, 10,400,200,10,10,400,200);
    };
    image.src = imageUrl

    //         var image = "screenshot/14.jpg"
    // 		var style = "width: " + boundsWidth + "px;" +
    // 			"height: " + boundsHeight + "px;" +
    // 			"display: block;"
    //             + "object-position: -"+boundsX+"px -"+boundsY+"px;"
    // 			+ "max-height: auto;" 
    // 			+ "max-width: auto;" 
    // 			// "background-repeat: no-repeat;"
    //             ;
    // 		var viewImg = "<div><img class=\"image-fluid\" src=\""+image+"\" style=\"" + style + "\" /></div>";
    //         container.html(viewImg)
}

$(document).ready(function () {
    loadData();
});


function redrawWidgetImage(actionId) {
    var actionData = data[actionId]
    var canvasId = "widget_canvas"+actionId
    if (actionData.targetWidget != null) {
        drawWidget(actionData,canvasId);
    }
}
function loadData() {
   overview()
}

function loadDataByActionId(actionId) {
    var actionData = data[actionId]
    $("#action-id").text(actionData.actionId)
    $("#action-type").text(actionData.actionType)
    if (actionData.targetWidget != null) {
        $("#triggered-widget-id").text(actionData.targetWidget.id)
        $('#triggered-widget-classname').text(actionData.targetWidget.className)
        $('#triggered-widget-text').text(actionData.targetWidget.text)
        drawWidget(actionData);
    }
    $("#source-state-id").text(actionData.from)
    $("#resulting-state-id").text(actionData.to)
    $("#action-data").text(actionData.data)
    $("#executed-statement-cnt").text(actionData.executedStatements)
    $("#executed-updated-instruction-cnt").text(actionData.executedUpdatedStatements)
    $("#new-executed-updated-instruction-cnt").text(actionData.newExecutedUpdatedStatements)
    var methods = "<ul>"
    for (let i=0; i < actionData.executedUpdatedMethods.length; i++) {
        var method = "<li>"
        method += escapeHtml(actionData.executedUpdatedMethods[i])
        method += "</li>"
        methods += method
    }
    methods += "</ul>"

    $("#executed-updated-methods").html(methods)
    const image = document.getElementById("image")
    image.src = actionData.image

    const prevImage = document.getElementById("prevImage")
    prevImage.src = actionData.prevImage
}
function escapeHtml(unsafe)
{
    return unsafe
         .replace(/&/g, "&amp;")
         .replace(/</g, "&lt;")
         .replace(/>/g, "&gt;")
         .replace(/"/g, "&quot;")
         .replace(/'/g, "&#039;");
 }
