package com.example.convert2video.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val ERROR_LOG_RETENTION_LIMIT = 300

/**
 * 영속 에러 로그 단일 진입점.
 * UI·Worker가 [ErrorLogDao]를 직접 임포트하는 것을 금지하고 이 Repository를 통해서만 접근한다.
 */
class ErrorLogRepository(
    private val dao: ErrorLogDao,
) {
    fun observeAll(): Flow<List<ErrorLogEntry>> = dao.observeAll()

    suspend fun insert(entry: ErrorLogEntry) {
        insertMutex.withLock {
            dao.insertAndTrim(entry, ERROR_LOG_RETENTION_LIMIT)
        }
    }

    suspend fun deleteAll() {
        insertMutex.withLock {
            dao.deleteAll()
        }
    }

    suspend fun deleteById(id: Long) {
        insertMutex.withLock {
            dao.deleteById(id)
        }
    }

    companion object {
        private val insertMutex = Mutex()

        fun create(context: Context): ErrorLogRepository =
            ErrorLogRepository(
                AppDatabase.getInstance(context).errorLogDao(),
            )
    }
}
