package team.startup.application

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import team.startup.application.domain.application.repository.SessionAttendanceMessage
import team.startup.application.domain.application.repository.SessionAttendanceOutboxRepository
import team.startup.application.domain.application.service.impl.SessionAttendanceDispatcher
import team.startup.application.domain.application.service.impl.SessionAttendanceGateway
import java.io.IOException
import java.util.UUID

class SessionAttendanceDispatcherTests {
    private val repository = mock(SessionAttendanceOutboxRepository::class.java)
    private val gateway = mock(SessionAttendanceGateway::class.java)
    private val dispatcher = SessionAttendanceDispatcher(repository, gateway)
    private val message = SessionAttendanceMessage(1, UUID.randomUUID(), 42, 1, 0)

    @Test
    fun `연결 장애는 재시도를 저장하고 배치를 멈춘다`() {
        `when`(repository.lockNext()).thenReturn(message)
        doAnswer { throw IOException("secret connection detail") }.`when`(gateway).send(message)
        assertFalse(dispatcher.dispatchNext())
        verify(repository).retry(message, "Attendance connection failure")
        verify(repository, never()).complete(message.id)
    }

    @Test
    fun `예상 밖 발송 예외도 비밀 값 없이 재시도를 저장하고 배치를 멈춘다`() {
        `when`(repository.lockNext()).thenReturn(message)
        doThrow(IllegalArgumentException("secret header detail")).`when`(gateway).send(message)
        assertFalse(dispatcher.dispatchNext())
        verify(repository).retry(message, "Attendance delivery failure")
        verify(repository, never()).complete(message.id)
    }

    @Test
    fun `HTTP 오류는 해당 참가자만 지연시키고 다른 참가자 배치를 계속한다`() {
        `when`(repository.lockNext()).thenReturn(message)
        doAnswer { throw IOException("Attendance HTTP 404") }.`when`(gateway).send(message)
        assertTrue(dispatcher.dispatchNext())
        verify(repository).retry(message, "Attendance HTTP 404")
    }

    @Test
    fun `서버 장애 응답도 재시도를 저장하고 배치를 멈춘다`() {
        `when`(repository.lockNext()).thenReturn(message)
        doAnswer { throw IOException("Attendance HTTP 503") }.`when`(gateway).send(message)
        assertFalse(dispatcher.dispatchNext())
        verify(repository).retry(message, "Attendance HTTP 503")
        verify(repository, never()).complete(message.id)
    }

    @Test
    fun `DB 재시도 기록 실패는 전파하여 트랜잭션이 롤백되게 한다`() {
        `when`(repository.lockNext()).thenReturn(message)
        doAnswer { throw IOException("connection failed") }.`when`(gateway).send(message)
        doThrow(
            DataAccessResourceFailureException("database unavailable"),
        ).`when`(repository).retry(message, "Attendance connection failure")
        assertThrows(DataAccessResourceFailureException::class.java) { dispatcher.dispatchNext() }
        verify(repository, never()).complete(message.id)
    }
}
