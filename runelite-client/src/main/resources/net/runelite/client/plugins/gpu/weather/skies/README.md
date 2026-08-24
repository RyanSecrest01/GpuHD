# Weather sky atlas inventory

All images use the renderer's 3-by-2 cubemap atlas layout. Keep the `sky###`
identifier when replacing or adding variants so code and visual reviews can
refer to the same asset unambiguously.

## Clear

- `clear/sky126_day_normal1.png` - primary calm daytime candidate
- `clear/sky128_day_normal3.png` - alternate textured daytime candidate

## Rain

- `rain/sky283_day_rain.png` - daytime rain
- `rain/sky280_sunset_rain.png` - sunset or transitional rain
- `rain/sky282_night_rain.png` - nighttime rain

## Snow

- `snow/sky273_day_snow.png` - daytime snow
- `snow/sky271_sunrise_snow.png` - sunrise or transitional snow
- `snow/sky272_night_snow.png` - nighttime snow
- `snow/sky278_thunder_snow.png` - snowstorm base
- `snow/sky279_thunder_snow_high.png` - severe snowstorm / flash variant

## Lightning

Paired stages are intended to be rendered in sequence, not held as a static
storm sky:

- `sky303_lightning1_stage1.png` -> `sky304_lightning1_stage2.png`
- `sky305_lightning2_stage1.png` -> `sky306_lightning2_stage2.png`
- `sky307_lightning3_stage1.png` -> `sky308_lightning3_stage2.png`
- `sky309_lightning4_stage1.png` -> `sky310_lightning4_stage2.png`

High-energy alternatives:

- `sky311_lightning_high1.png`
- `sky312_lightning_high2.png`
- `sky313_lightning_high3.png`
- `sky314_lightning_high4.png`

## Special events

- `special/sky326_fiery_sunset_low.png`
- `special/sky327_fiery_sunset_high.png`
- `special/sky328_noctilucent_low.png`
- `special/sky329_noctilucent_high.png`
- `special/sky330_iridescent_clouds_1.png`
- `special/sky331_iridescent_clouds_2.png`
- `special/sky332_iridescent_clouds_3.png`
