/* Dial 3000 presentation; existing GT01 data sources and subscriptions. */
var page = null;
var uri = null;
var dialId = "";
var health = require("service.health");
var battery = require("system.battery");
var emq = require("system.emq");
var wear = require("system.wear");

module.exports = {
  subscriptions: [],
  timeSubscription: null,
  batterySubscription: null,
  distanceSubscription: null,
  unitSubscription: null,
  screenSubscription: null,
  wearSubscription: null,
  active: false,
  generation: 0,

  onLoad: function (value) {
    page = value.page;
    uri = value.uri;
    dialId = dial.parse(uri).id + "p";
    return this.allValues();
  },
  onReady: function () { this.publish(this.allValues()); },
  onShow: function () {
    var self = this;
    if (this.active) return;
    this.active = true;
    this.generation++;
    var current = this.generation;
    try {
      this.publish(this.allValues());
      this.timeSubscription = {hub: "emqDialTime", channel: 0, callback: function () { self.emit(current, function () { return self.timeValues(); }); }};
      emq.onMessage(this.timeSubscription);
      this.batterySubscription = {callback: function () { self.emit(current, function () { return self.batteryValue(); }); }};
      battery.onLevelChange(this.batterySubscription);
      this.subscribeHealth("target.steps", function () { return self.stepsValue(); }, current);
      this.subscribeHealth("target.calorie", function () { return self.calorieValue(); }, current);
      this.subscribeHealth("heartrateRealValue", function () { return self.heartValue(); }, current);
      this.distanceSubscription = {key: "TodayDistance", callback: function () { self.emit(current, function () { return self.distanceValue(); }); }};
      systemStorage.onChange(this.distanceSubscription);
      this.unitSubscription = {key: "unitLength", callback: function () { self.emit(current, function () { return self.distanceValue(); }); }};
      systemStorage.onChange(this.unitSubscription);
      this.screenSubscription = {key: "screen.isScreenLocked", callback: function () { self.emit(current, function () { return self.allValues(); }); }};
      systemStorage.onChange(this.screenSubscription);
      this.wearSubscription = {callback: function () { self.emit(current, function () { return self.heartValue(); }); }};
      wear.onStatusChange(this.wearSubscription);
    } catch (error) {
      this.close();
      throw error;
    }
  },
  onHide: function () { this.close(); },
  onExit: function () { this.close(); },
  close: function () {
    var i;
    if (!this.active) return;
    this.active = false;
    this.generation++;
    try { if (this.timeSubscription) emq.offMessage(this.timeSubscription); } catch (error) {}
    try { if (this.batterySubscription) battery.offLevelChange(this.batterySubscription); } catch (error) {}
    try { if (this.distanceSubscription) systemStorage.offChange(this.distanceSubscription); } catch (error) {}
    try { if (this.unitSubscription) systemStorage.offChange(this.unitSubscription); } catch (error) {}
    try { if (this.screenSubscription) systemStorage.offChange(this.screenSubscription); } catch (error) {}
    try { if (this.wearSubscription) wear.offStatusChange(this.wearSubscription); } catch (error) {}
    for (i = 0; i < this.subscriptions.length; i++) {
      try { health.unsubscribeValueChange(this.subscriptions[i]); } catch (error) {}
    }
    this.subscriptions = [];
    this.timeSubscription = null;
    this.batterySubscription = null;
    this.distanceSubscription = null;
    this.unitSubscription = null;
    this.screenSubscription = null;
    this.wearSubscription = null;
  },
  emit: function (generation, reader) {
    if (this.active && generation === this.generation) this.publish(reader());
  },
  publish: function (values) {
    var data = {};
    data[dialId] = {data: values};
    page.setData(data, uri);
  },
  subscribeHealth: function (datatype, reader, generation) {
    var self = this;
    var subscription = {datatype: datatype, callback: function () { self.emit(generation, reader); }};
    this.subscriptions.push(subscription);
    health.subscribeValueChange(subscription);
  },
  text: function (value) { return {value: String(value)}; },
  two: function (value) { return value < 10 ? "0" + value : String(value); },
  screenVisible: function () {
    var value = systemStorage.getItem("screen.isScreenLocked");
    return value === undefined ? true : !!value;
  },
  timeValues: function () {
    var now = new Date();
    var days = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];
    var hour = this.two(now.getHours());
    var minute = this.two(now.getMinutes());
    return {
      H10: {value: "images/digit_" + hour[0] + ".png"},
      H01: {value: "images/digit_" + hour[1] + ".png"},
      M10: {value: "images/digit_" + minute[0] + ".png"},
      M01: {value: "images/digit_" + minute[1] + ".png"},
      TIME_S: this.text(this.two(now.getSeconds())),
      TIME_M_D: this.text(this.two(now.getDate()) + "." + this.two(now.getMonth() + 1) + "." + now.getFullYear()),
      TIME_W: this.text(days[now.getDay()])
    };
  },
  stepsValue: function () {
    var raw = health.getValueSync({datatype: "target.steps"});
    var value = Number(raw);
    return {STEPS: this.text(this.screenVisible() && raw != null && isFinite(value) ? Math.min(999999, Math.max(0, Math.floor(value))) : "--")};
  },
  calorieValue: function () {
    var raw = health.getValueSync({datatype: "target.calorie"});
    var value = Number(raw);
    return {KCAL: this.text(this.screenVisible() && raw != null && isFinite(value) ? Math.min(9999, Math.max(0, Math.round(value))) : "--")};
  },
  batteryValue: function () {
    var raw = battery.getLevelSync();
    var value = Number(raw);
    if (raw == null || !isFinite(value)) return {BATTERY: this.text("--"), HUD: {value: "images/bat_0.png"}};
    var percent = Math.min(100, Math.max(0, Math.floor(value)));
    return {BATTERY: this.text(percent + "%"), HUD: {value: "images/bat_" + Math.floor(percent / 10) + ".png"}};
  },
  heartValue: function () {
    var data = health.getValueSync({datatype: "heartrateRealValue"});
    var value = Number(data);
    var status = wear.getStatusSync();
    if (!this.screenVisible() || !status || status.status !== "on" || data == null || !isFinite(value) || value <= 0) value = "--";
    else value = Math.min(999, Math.floor(value));
    return {HEART: this.text(value)};
  },
  distanceValue: function () {
    var raw = systemStorage.getItem("TodayDistance");
    var value = Number(raw);
    if (!this.screenVisible() || raw == null || !isFinite(value)) return {DISTAN: this.text("--")};
    var scaled = value * 1000;
    var digits = (scaled / Math.pow(10, 5)).toFixed(5).substr(2);
    if (scaled >= 10000) return {DISTAN: this.text(digits[0] + digits[1] + "." + digits[2] + digits[3])};
    if (scaled >= 1000) return {DISTAN: this.text(digits[1] + "." + digits[2] + digits[3])};
    if (scaled >= 100) return {DISTAN: this.text("0." + digits[2] + digits[3])};
    if (scaled >= 10) return {DISTAN: this.text("0.0" + digits[3])};
    if (scaled >= 0 && scaled < 10) return {DISTAN: this.text("0.00")};
    return {DISTAN: this.text("99.99")};
  },
  allValues: function () {
    var result = this.timeValues();
    var parts = [this.stepsValue(), this.calorieValue(), this.batteryValue(), this.heartValue(), this.distanceValue()];
    for (var i = 0; i < parts.length; i++) for (var key in parts[i]) result[key] = parts[i][key];
    return result;
  }
};
