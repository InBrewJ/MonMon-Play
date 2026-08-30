$ ->
    formatTicker = ->
        now = new Date()
        day = now.getDate()
        suffix = "th"
        if day in [1, 21, 31]
            suffix = "st"
        else if day in [2, 22]
            suffix = "nd"
        else if day in [3, 23]
            suffix = "rd"

        dateStr = now.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
        timeStr = now.toLocaleTimeString('en-GB')
        $("#ticker").html "<span style='color: #60a5fa; font-weight: 700; margin-right: 8px;'><i class='far fa-calendar-alt'></i> Today: Day #{day} (#{day}#{suffix})</span> · <span style='color: #cbd5e1;'>#{dateStr} #{timeStr}</span>"

    formatTicker()
    setInterval formatTicker, 1000

    if window.location.hash == "#edit-form" or $("#edit-form.editing-mode").length
        editTarget = document.getElementById("edit-form")
        if editTarget
            editTarget.scrollIntoView({ behavior: 'smooth', block: 'start' })
            setTimeout ->
                $("#edit-form input:visible:first").focus()
            , 200

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

