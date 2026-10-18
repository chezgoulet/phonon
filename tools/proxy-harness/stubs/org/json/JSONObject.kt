package org.json
class JSONObject() {
  private val m = LinkedHashMap<String, Any?>()
  constructor(text: String) : this() {
    val t = text.trim(); if (!t.startsWith("{")) throw RuntimeException("bad json")
    Regex("\"(\\w+)\"\\s*:\\s*(\"([^\"]*)\"|null|[^,}]+)").findAll(t).forEach {
      m[it.groupValues[1]] = if (it.groupValues[2]=="null") null else it.groupValues[3].ifEmpty { it.groupValues[2] } }
  }
  fun optString(k:String) = (m[k] ?: "").toString()
  fun put(k:String, v:Any?): JSONObject { m[k]=v; return this }
  fun has(k:String) = m.containsKey(k)
  fun isNull(k:String) = m[k]==null
  override fun toString() = m.entries.joinToString(",","{","}") { "\"${it.key}\":" + (it.value?.let{v-> if (v is String) "\"$v\"" else v.toString()} ?: "null") }
}
