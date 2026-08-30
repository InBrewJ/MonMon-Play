$ ->
    $("#ticker").html new Date()
    setInterval ->
        $("#ticker").html new Date()
    , 1000

$ ->
    updateAllowances = (percent) ->
        box = $("#allowances-box")
        if box.length
            incoming = parseFloat(box.data("incoming")) || 0
            outgoing = parseFloat(box.data("outgoing")) || 0
            days = parseInt(box.data("days")) || 30
            days = 30 if days <= 0

            pct = parseFloat(percent) || 0
            savingsCost = (pct / 100.0) * incoming
            newSurplus = (incoming - outgoing) - savingsCost
            newSurplus = 0 if newSurplus < 0
            newMaxDay = newSurplus / days
            newMaxWeek = newMaxDay * 7
            newYearlySurplus = newSurplus * 12

            $("#dyn-max-day").text(newMaxDay.toFixed(2))
            $("#dyn-max-week").text(newMaxWeek.toFixed(2))
            $("#dyn-max-month").text(newSurplus.toFixed(2))
            $("#dyn-yearly-surplus").text(newYearlySurplus.toFixed(2))
            $("#savings-amount").text("£" + savingsCost.toFixed(2))
            $("#savings-slider-val").text(pct)
            $("#savings-slider-val-target").attr("href", "/plans/updateSavingsPayoffPercentage?percent=" + pct)

    initialSliderVal = $("#savings-slider").val()
    if initialSliderVal?
        updateAllowances(initialSliderVal)

    $("#savings-slider").on "input change", (e) =>
        updateAllowances(e.target.value)

$ ->
    $(document).on "click", "a[href*='/bin'], .btn-delete, a[title*='Delete'], a[title*='Delete Pot']", (e) ->
        unless confirm("Are you sure you want to delete this?")
            e.preventDefault()
            return false

