package app.tenshu.hub

/**
 * Recorded-shape zigbee2mqtt payloads (the formats the z2m documentation
 * publishes for `bridge/devices`, `bridge/state`, `bridge/event` and device
 * state topics), trimmed of the fields we never look at but otherwise
 * verbatim — including the ones that exist purely to be ignored.
 *
 * These are the HD-2 tests' ground truth: the bridge is only as good as its
 * behaviour on real payloads, and "real" here means *upstream's* shapes, not
 * shapes invented to match our parser.
 */
object Zigbee2MqttFixtures {

    /** A dimmable, colour-temperature IKEA bulb + an Aqara climate sensor + one device z2m has no definition for. */
    val BRIDGE_DEVICES = """
        [
          {
            "definition": null,
            "friendly_name": "Coordinator",
            "ieee_address": "0x00124b00120144ae",
            "interview_completed": true,
            "interviewing": false,
            "network_address": 0,
            "supported": true,
            "type": "Coordinator"
          },
          {
            "date_code": "20180416",
            "definition": {
              "description": "TRADFRI LED bulb E27 980 lumen, dimmable, white spectrum",
              "exposes": [
                {
                  "features": [
                    {
                      "access": 7, "name": "state", "property": "state", "type": "binary",
                      "value_off": "OFF", "value_on": "ON", "value_toggle": "TOGGLE"
                    },
                    {
                      "access": 7, "name": "brightness", "property": "brightness",
                      "type": "numeric", "value_max": 254, "value_min": 0
                    },
                    {
                      "access": 7, "name": "color_temp", "property": "color_temp",
                      "type": "numeric", "unit": "mired", "value_max": 454, "value_min": 250
                    }
                  ],
                  "type": "light"
                },
                {
                  "access": 2, "name": "effect", "property": "effect", "type": "enum",
                  "values": ["blink", "breathe", "okay"]
                },
                {
                  "access": 1, "name": "linkquality", "property": "linkquality",
                  "type": "numeric", "unit": "lqi", "value_max": 255, "value_min": 0
                }
              ],
              "model": "LED1545G12",
              "supports_ota": true,
              "vendor": "IKEA"
            },
            "friendly_name": "Living room lamp",
            "ieee_address": "0x000b57fffec6a5b2",
            "interview_completed": true,
            "interviewing": false,
            "manufacturer": "IKEA of Sweden",
            "model_id": "TRADFRI bulb E27 WS opal 980lm",
            "network_address": 35827,
            "power_source": "Mains (single phase)",
            "supported": true,
            "type": "Router"
          },
          {
            "definition": {
              "description": "Temperature and humidity sensor",
              "exposes": [
                {
                  "access": 1, "name": "battery", "property": "battery", "type": "numeric",
                  "unit": "%", "value_max": 100, "value_min": 0
                },
                {
                  "access": 1, "name": "temperature", "property": "temperature",
                  "type": "numeric", "unit": "°C"
                },
                {
                  "access": 1, "name": "humidity", "property": "humidity",
                  "type": "numeric", "unit": "%"
                },
                {
                  "access": 1, "name": "pressure", "property": "pressure",
                  "type": "numeric", "unit": "hPa"
                },
                {
                  "access": 1, "name": "linkquality", "property": "linkquality",
                  "type": "numeric", "unit": "lqi"
                }
              ],
              "model": "WSDCGQ11LM",
              "vendor": "Aqara"
            },
            "friendly_name": "Bedroom sensor",
            "ieee_address": "0x00158d0001b2c3d4",
            "interview_completed": true,
            "interviewing": false,
            "power_source": "Battery",
            "supported": true,
            "type": "EndDevice"
          },
          {
            "definition": null,
            "friendly_name": "0x00158d00099e8877",
            "ieee_address": "0x00158d00099e8877",
            "interview_completed": true,
            "interviewing": false,
            "manufacturer": "_TZ3000_mystery",
            "model_id": "TS0601",
            "power_source": "Battery",
            "supported": false,
            "type": "EndDevice"
          }
        ]
    """.trimIndent()

    /** The same list after the user renamed the lamp in zigbee2mqtt (IEEE unchanged). */
    val BRIDGE_DEVICES_AFTER_RENAME = BRIDGE_DEVICES.replace("Living room lamp", "Reading lamp")

    const val LAMP_IEEE = "0x000b57fffec6a5b2"
    const val SENSOR_IEEE = "0x00158d0001b2c3d4"
    const val MYSTERY_IEEE = "0x00158d00099e8877"

    val LAMP_ID = Zigbee2Mqtt.deviceId(LAMP_IEEE)
    val SENSOR_ID = Zigbee2Mqtt.deviceId(SENSOR_IEEE)
    val MYSTERY_ID = Zigbee2Mqtt.deviceId(MYSTERY_IEEE)

    /** `zigbee2mqtt/Living room lamp` — note the nested `update` object we must not choke on. */
    val LAMP_STATE = """
        {
          "brightness": 128,
          "color_mode": "color_temp",
          "color_temp": 370,
          "linkquality": 102,
          "state": "ON",
          "update": {"installed_version": 16777231, "state": "idle"},
          "update_available": false
        }
    """.trimIndent()

    /** `zigbee2mqtt/Bedroom sensor` — decimals, and attributes we do not model. */
    val SENSOR_STATE = """
        {
          "battery": 87,
          "humidity": 42.3,
          "linkquality": 60,
          "pressure": 1001,
          "temperature": 21.53,
          "voltage": 3005
        }
    """.trimIndent()

    /** A device whose every attribute is unmodelled — it must still become a state row. */
    val MYSTERY_STATE = """
        {"linkquality": 43, "unknown_1": 5, "unknown_2": "weird", "last_seen": "2026-07-25T10:00:00Z"}
    """.trimIndent()

    val BRIDGE_ONLINE = """{"state":"online"}"""
    val BRIDGE_OFFLINE = """{"state":"offline"}"""

    val DEVICE_JOINED_EVENT = """
        {"type":"device_joined","data":{"friendly_name":"0x0017880104e45517","ieee_address":"0x0017880104e45517"}}
    """.trimIndent()

    /**
     * The adversarial case (threat model T-HD-04): a device whose name comes
     * off the air carrying newlines, terminal escapes, zero-width characters
     * and 400 characters of padding. Nothing stops a neighbour from joining
     * this to *their* network and letting it announce itself into ours during
     * a pairing window.
     */
    val HOSTILE_DEVICES = """
        [
          {
            "definition": {"model": "M O\nD", "vendor": "\u001b[31mEVIL\u001b[0m", "exposes": []},
            "friendly_name": "Kitchen\r\n\u200blamp ${"A".repeat(400)}",
            "ieee_address": "0x00158d0009ffeedd",
            "type": "EndDevice"
          }
        ]
    """.trimIndent()

    const val HOSTILE_IEEE = "0x00158d0009ffeedd"
}
