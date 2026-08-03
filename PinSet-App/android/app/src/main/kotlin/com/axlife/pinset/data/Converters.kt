package com.axlife.pinset.data

import androidx.room.TypeConverter
import com.axlife.pinset.data.entity.DefectStatus
import com.axlife.pinset.data.entity.DefectType
import com.axlife.pinset.data.entity.Lens
import com.axlife.pinset.data.entity.PinSource
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.data.entity.SlotRole
import com.axlife.pinset.data.entity.Surface
import com.axlife.pinset.data.entity.Trade

class Converters {
    @TypeConverter fun typeToStr(v: DefectType) = v.name
    @TypeConverter fun strToType(v: String) = DefectType.valueOf(v)
    @TypeConverter fun sevToStr(v: Severity) = v.name
    @TypeConverter fun strToSev(v: String) = Severity.valueOf(v)
    @TypeConverter fun srcToStr(v: PinSource) = v.name
    @TypeConverter fun strToSrc(v: String) = PinSource.valueOf(v)
    @TypeConverter fun lensToStr(v: Lens) = v.name
    @TypeConverter fun strToLens(v: String) = Lens.valueOf(v)
    @TypeConverter fun slotToStr(v: SlotRole) = v.name
    @TypeConverter fun strToSlot(v: String) = SlotRole.valueOf(v)
    @TypeConverter fun tradeToStr(v: Trade) = v.name
    @TypeConverter fun strToTrade(v: String) = Trade.valueOf(v)
    @TypeConverter fun surfToStr(v: Surface) = v.name
    @TypeConverter fun strToSurf(v: String) = Surface.valueOf(v)
    @TypeConverter fun statusToStr(v: DefectStatus) = v.name
    @TypeConverter fun strToStatus(v: String) = DefectStatus.valueOf(v)
}
