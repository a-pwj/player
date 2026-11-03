package android.net

/**
 * 纯 JVM 单测用的 Uri 替身。
 *
 * `android.net.Uri` 是无参构造器为包级私有的抽象类，`Uri.parse` 在单测里又是被剥掉的方法体，
 * 所以这个类必须放在 `android.net` 包下才能访问它的构造器；其余成员只是把字符串原样返回。
 */
internal class TestUri(private val text: String) : Uri() {
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = "file"
    override fun getSchemeSpecificPart(): String = text
    override fun getEncodedSchemeSpecificPart(): String = text
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String? = null
    override fun getPort(): Int = -1
    override fun getPath(): String = text
    override fun getEncodedPath(): String = text
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): MutableList<String> = mutableListOf(text.substringAfterLast('/'))
    override fun getLastPathSegment(): String? = text.substringAfterLast('/')
    override fun toString(): String = text
    override fun buildUpon(): Uri.Builder = throw UnsupportedOperationException("单测里不需要构造新 Uri")
    override fun describeContents(): Int = 0
    override fun writeToParcel(out: android.os.Parcel, flags: Int) = Unit
    override fun compareTo(other: Uri?): Int = text.compareTo(other?.toString().orEmpty())
}
