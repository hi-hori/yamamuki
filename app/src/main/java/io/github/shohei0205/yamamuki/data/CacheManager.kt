package io.github.shohei0205.yamamuki.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 設定画面に出すキャッシュの状況。 */
data class CacheInfo(
    val mountainCount: Int,
    val tileCount: Int,
    /** データベースのファイル(WAL などの付随ファイルを含む)の合計。 */
    val sizeBytes: Long,
)

/** 山データのキャッシュ(Room)の状況確認と消去。 */
class CacheManager(private val context: Context, private val database: MountainDatabase) {
    private val dao = database.mountainDao()

    suspend fun info(): CacheInfo = CacheInfo(
        mountainCount = dao.countMountains(),
        tileCount = dao.countTiles(),
        sizeBytes = withContext(Dispatchers.IO) { databaseFileBytes() },
    )

    /** 山と取得済みタイルをすべて消す。次の表示で現在地周辺を取り直す。 */
    suspend fun clear() {
        dao.clearAll()
        // 行を消しただけではファイルは縮まないので、WAL を書き戻してから VACUUM で詰める。
        withContext(Dispatchers.IO) {
            val db = database.openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            db.execSQL("VACUUM")
        }
    }

    private fun databaseFileBytes(): Long {
        val main = context.getDatabasePath(MountainDatabase.FILE_NAME)
        return listOf("", "-wal", "-shm", "-journal")
            .map { java.io.File(main.path + it) }
            .filter { it.exists() }
            .sumOf { it.length() }
    }
}
