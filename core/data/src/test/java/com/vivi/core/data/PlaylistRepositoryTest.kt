package com.vivi.core.data

import com.vivi.core.data.db.PlaylistDao
import com.vivi.core.data.db.PlaylistEntity
import com.vivi.core.data.db.PlaylistItemDao
import com.vivi.core.data.db.PlaylistItemEntity
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.VideoMedia
import com.vivi.core.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePlaylistDao : PlaylistDao {
    val store = LinkedHashMap<String, PlaylistEntity>()

    override suspend fun put(playlist: PlaylistEntity) {
        store[playlist.id] = playlist
    }

    override fun observeAll(): Flow<List<PlaylistEntity>> = flow {
        emit(store.values.sortedBy { it.createdAtMillis }.toList())
    }

    override suspend fun get(id: String): PlaylistEntity? = store[id]

    override suspend fun rename(id: String, name: String) {
        store[id]?.let { store[id] = it.copy(name = name) }
    }

    override suspend fun delete(id: String) {
        store.remove(id)
    }
}

/** 模拟 Room 的 (playlist_id, media_key) 联合主键与 IGNORE 冲突策略。 */
private class FakePlaylistItemDao : PlaylistItemDao {
    val rows = mutableListOf<PlaylistItemEntity>()

    private fun find(playlistId: String, key: String) =
        rows.firstOrNull { it.playlistId == playlistId && it.mediaKey == key }

    override suspend fun add(item: PlaylistItemEntity): Long {
        if (find(item.playlistId, item.mediaKey) != null) return -1L
        rows += item
        return rows.size.toLong()
    }

    override fun observeFor(playlistId: String): Flow<List<PlaylistItemEntity>> = flow {
        emit(rows.filter { it.playlistId == playlistId }.sortedBy { it.position }.toList())
    }

    override fun observeAll(): Flow<List<PlaylistItemEntity>> = flow { emit(rows.toList()) }

    override suspend fun maxPosition(playlistId: String): Int? =
        rows.filter { it.playlistId == playlistId }.maxOfOrNull { it.position }

    override suspend fun remove(playlistId: String, key: String) {
        rows.removeAll { it.playlistId == playlistId && it.mediaKey == key }
    }

    override suspend fun clear(playlistId: String) {
        rows.removeAll { it.playlistId == playlistId }
    }

    override suspend fun setPosition(playlistId: String, key: String, position: Int) {
        find(playlistId, key)?.let { row ->
            rows[rows.indexOf(row)] = row.copy(position = position)
        }
    }
}

class PlaylistRepositoryTest {

    private val dao = FakePlaylistDao()
    private val itemDao = FakePlaylistItemDao()

    /** 解析器：只有名字里带「_gone」的文件视为已被移走。 */
    private fun repository() = PlaylistRepository(dao, itemDao) { keys ->
        keys.mapNotNull { key ->
            val name = key.value.substringAfterLast('/')
            if (name.contains("_gone")) null else video(name = name, path = key.value).copy(key = key)
        }
    }

    private fun key(name: String) = MediaKey.ofPath("/storage/emulated/0/Movies/$name")

    @Test
    fun `create 生成 id 并落到存储里`() = runTest {
        val repo = repository()
        val id = repo.create("周末片单")
        assertTrue(id.isNotBlank())
        assertEquals("周末片单", dao.store.getValue(id).name)
    }

    @Test
    fun `create 空白名称回落到默认名`() = runTest {
        val repo = repository()
        val id = repo.create("   ")
        assertEquals("未命名列表", dao.store.getValue(id).name)
    }

    @Test
    fun `add 按加入顺序编号且重复加入被忽略`() = runTest {
        val repo = repository()
        val id = repo.create("片单")
        repo.add(id, key("a.mp4"))
        repo.add(id, key("b.mp4"))
        repo.add(id, key("a.mp4"))
        assertEquals(listOf(0, 1), itemDao.rows.map { it.position })
        assertEquals(2, itemDao.rows.size)
    }

    @Test
    fun `entriesOnce 保持列表顺序并跳过已移走的文件`() = runTest {
        val repo = repository()
        val id = repo.create("片单")
        repo.add(id, key("c.mp4"))
        repo.add(id, key("a_gone.mp4"))
        repo.add(id, key("b.mp4"))
        val entries = repo.entriesOnce(id)
        assertEquals(listOf("c.mp4", "b.mp4"), entries.map { it.displayName })
    }

    @Test
    fun `reorder 重写位置`() = runTest {
        val repo = repository()
        val id = repo.create("片单")
        listOf("a.mp4", "b.mp4", "c.mp4").forEach { repo.add(id, key(it)) }
        repo.reorder(id, listOf(key("c.mp4"), key("a.mp4"), key("b.mp4")))
        assertEquals(listOf("c.mp4", "a.mp4", "b.mp4"), repo.entriesOnce(id).map { it.displayName })
    }

    @Test
    fun `remove 只删一条关联`() = runTest {
        val repo = repository()
        val id = repo.create("片单")
        repo.add(id, key("a.mp4"))
        repo.add(id, key("b.mp4"))
        repo.remove(id, key("a.mp4"))
        assertEquals(listOf("b.mp4"), repo.entriesOnce(id).map { it.displayName })
    }

    @Test
    fun `delete 同时清掉条目`() = runTest {
        val repo = repository()
        val id = repo.create("片单")
        repo.add(id, key("a.mp4"))
        repo.delete(id)
        assertNull(dao.store[id])
        assertTrue(itemDao.rows.isEmpty())
    }

    @Test
    fun `rename 忽略空白名称`() = runTest {
        val repo = repository()
        val id = repo.create("原名")
        repo.rename(id, "  ")
        assertEquals("原名", dao.store.getValue(id).name)
        repo.rename(id, " 新名 ")
        assertEquals("新名", dao.store.getValue(id).name)
    }

    @Test
    fun `observePlaylists 统计每个列表的条数`() = runTest {
        val repo = repository()
        val first = repo.create("A")
        val second = repo.create("B")
        repo.add(first, key("a.mp4"))
        repo.add(first, key("b.mp4"))
        val playlists = repo.observePlaylists.first()
        assertEquals(listOf("A", "B"), playlists.map { it.name })
        assertEquals(listOf(2, 0), playlists.map { it.itemCount })
        assertEquals(second, playlists[1].id)
    }

    @Test
    fun `observeEntries 解析出的条目带正确的 key`() = runTest {
        val repo = repository()
        val id = repo.create("片单")
        repo.add(id, key("a.mp4"))
        val entries: List<VideoMedia> = repo.observeEntries(id).first()
        assertEquals(1, entries.size)
        assertEquals(key("a.mp4"), entries.first().key)
    }
}
