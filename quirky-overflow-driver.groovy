/*
Quirky Moisture Sensor
Based off of https://community.smartthings.com/t/quirky-overflow/18359/35
*/
metadata {
  definition (name: "Quirky Moisture Sensor",namespace: "tierneykev", author: "Kevin Tierney") {
    capability "Configuration" // https://docs.hubitat.com/index.php?title=Driver_Capability_List#Configuration
    // Dev Note: For Configuration must define command "configure()"

    capability "Battery" // https://docs.hubitat.com/index.php?title=Driver_Capability_List#Battery
    // Dev Note: For Battery must define attribute "battery" with a number value

    capability "Refresh" // https://docs.hubitat.com/index.php?title=Driver_Capability_List#Refresh
    // Dev Note:  For Refresh must define command "refresh()"

    capability "WaterSensor" // https://docs.hubitat.com/index.php?title=Driver_Capability_List#WaterSensor
    // Dev Note: For WaterSensor must define attribute "water" with values "wet" or "dry"

    command "enrollResponse"

    // based on https://zigbeealliance.org/wp-content/uploads/2019/12/07-5123-06-zigbee-cluster-library-specification.pdf and
    // https://www.nxp.com/docs/en/user-guide/JN-UG-3077.pdf
    // 0000 - Basic
    // 0001- Power configuration
    // 0003 - Identity
    // 0019 - OTA Upgrade
    // 0020 - poll control
    // 0b05 - Diagnostics
    // 0500 - Intruder Alarm System (IAS) Zone
    fingerprint profileId: "0104", inClusters: "0000,0001,0003,0500,0020,0B05", outClusters: "0003,0019", model:"Overflow"
  }
}

// From Hubitat documentation: Called in response to a message received by the device driver.
// Depending on the type of message received you will likely need to parse the description string into something more useful.
// The following list of methods are useful for decoding the description:
// Zigbee - zigbee.parse
def parse(String description) {
  if (logEnable) log.debug "parse: ${description}"

  Map map = [:]
  if (description?.startsWith('catchall:')) {
    map = parseCatchAllMessage(description)
  } else if (description?.startsWith('read attr -')) {
    map = parseReportAttributeMessage(description)
  } else if (description?.startsWith('zone status')) {
    map = parseIasMessage(description)
  }

  if (logEnable) log.debug "Parse returned $map"
  def result = map ? createEvent(map) : null

  if (description?.startsWith('enroll request')) {
    List cmds = enrollResponse()
    if (logEnable) log.debug "enroll response: ${cmds}"
    result = cmds?.collect { new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE) }
  }
  return result
}

private Map parseCatchAllMessage(String description) {
  Map resultMap = [:]
  def descMap = zigbee.parseDescriptionAsMap(description)
  if (logEnable) log.debug "parseCatchAllMessage, descMap: ${descMap}"
  if (shouldProcessMessage(descMap)) {
    switch(cluster.clusterId) {
      case 0x0001:
       	resultMap = getBatteryResult(cluster.data.last())
        break
    }
  }

  return resultMap
}

private boolean shouldProcessMessage(cluster) {
  // 0x0B is default response indicating message got through
  // 0x07 is bind message
  boolean ignoredMessage = cluster.profileId != 0x0104 || 
      cluster.command == 0x0B ||
      cluster.command == 0x07 ||
      (cluster.data.size() > 0 && cluster.data.first() == 0x3e)
  return !ignoredMessage
}

private Map parseReportAttributeMessage(String description) {
  Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
    def nameAndValue = param.split(":")
    map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
  }
  if (logEnable) log.debug "Desc Map: ${descMap}"

  Map resultMap = [:]
  if (descMap.cluster == "0001" && descMap.attrId == "0020") {
    resultMap = getBatteryResult(Integer.parseInt(descMap.value, 16))
  }

  return resultMap
}

private Map parseIasMessage(String description) {
  List parsedMsg = description.split(' ')
  String msgCode = parsedMsg[2]

  Map resultMap = [:]
  switch(msgCode) {
    case '0x0022': // Tamper Alarm
      break

    case '0x0023': // Battery Alarm
      break

    case '0x0024': // Supervision Report
      if (logEnable) log.debug 'dry with tamper alarm'
      resultMap = getMoistureResult('dry')
      break

    case '0x0025': // Restore Report
      if (logEnable) log.debug 'water with tamper alarm'
      resultMap = getMoistureResult('wet')
      break

    case '0x0026': // Trouble/Failure
      break

    case '0x0028': // Test Mode
      break
            
    case '0x0030': // Closed/No Motion/Dry
      resultMap = getMoistureResult('dry')
      break

    case '0x0031': // Open/Motion/Wet
      resultMap = getMoistureResult('wet')
      break
  }
  return resultMap
}

private Map getBatteryResult(rawValue) {
  if (logEnable) log.debug 'Battery'
  def linkText = getLinkText(device)

  def result = [
    name: 'battery', value: '100', descriptionText: 'text'
  ]

  def volts = rawValue / 10
  def descriptionText
  if (volts > 3.5) {
    result.descriptionText = "${linkText} battery has too much power (${volts} volts)."
  } else {
    def minVolts = 2.1
    def maxVolts = 3.0
    def pct = (volts - minVolts) / (maxVolts - minVolts)
    result.value = (int) (pct * 100)
    result.descriptionText = "${linkText} battery was ${result.value}%"
  }

  return result
}

private Map getMoistureResult(value) {
  if (logEnable) log.debug 'water'
  String descriptionText = "${device.displayName} is ${value}"
  return [
    name: 'water',
    value: value,
    descriptionText: descriptionText
  ]
}

// Required for the 'refresh' capability.
def refresh() {
  if (logEnable) log.debug "Refresh."
  // Read battery
  [
    "he rattr 0x${device.deviceNetworkId} 1 1 0x20"
  ]
}

// Required for the 'configure' capability.
def configure() {
  String zigbeeId = swapEndianHex(device.hub.zigbeeId)
  if (logEnable) log.debug "Configuring Reporting, IAS CIE, and Bindings."
  def configCmds = [	
    "zcl global write 0x500 0x10 0xf0 {${zigbeeId}}", "delay 200",
    "send 0x${device.deviceNetworkId} 1 1", "delay 1500",

    "zcl global send-me-a-report 1 0x20 0x20 300 0600 {01}", "delay 200",
    "send 0x${device.deviceNetworkId} 1 1", "delay 1500",

    "zdo bind 0x${device.deviceNetworkId} 1 1 0x001 {${device.zigbeeId}} {}", "delay 1000",

    // Per: https://community.hubitat.com/t/need-help-converting-st-zigbee-dth-to-he/13658
    "he raw 0x${device.deviceNetworkId} 1 1 0x500 {01 23 00 00 00}", "delay 1200",
  ]
  return configCmds + refresh() // send refresh cmds as part of config
}

// Declared for the enrollResponse command.
def enrollResponse() {
  if (logEnable) log.debug "Sending enroll response"
  [
    // Per: https://community.hubitat.com/t/need-help-converting-st-zigbee-dth-to-he/13658
    "he raw 0x${device.deviceNetworkId} 1 1 0x500 {01 23 00 00 00}", "delay 200",
  ]
}

private hex(value) {
  new BigInteger(Math.round(value).toString()).toString(16)
}

private String swapEndianHex(String hex) {
  reverseArray(hex.decodeHex()).encodeHex()
}

private byte[] reverseArray(byte[] array) {
  int i = 0;
  int j = array.length - 1;
  byte tmp;
  while (j > i) {
    tmp = array[j];
    array[j] = array[i];
    array[i] = tmp;
    j--;
    i++;
  }
  return array
}
