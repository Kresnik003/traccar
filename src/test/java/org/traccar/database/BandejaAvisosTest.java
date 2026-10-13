package org.traccar.database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BandejaAvisosTest {

    private static DataSource base(PreparedStatement statement) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        return dataSource;
    }

    @Test
    public void testAnota() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        assertTrue(new BandejaAvisos(base(statement)).anotar(7, 42));
        verify(statement).setLong(1, 7);
        verify(statement).setLong(2, 42);
        verify(statement).executeUpdate();
    }

    @Test
    public void testSinEventoNoSeAnota() throws Exception {
        // An event that could not be saved has id 0: no row, and no query.
        DataSource dataSource = mock(DataSource.class);
        assertFalse(new BandejaAvisos(dataSource).anotar(7, 0));
        assertFalse(new BandejaAvisos(dataSource).anotar(0, 42));
        verify(dataSource, never()).getConnection();
    }

    @Test
    public void testRepetidoNoEsError() throws Exception {
        // The same event matching two notifications of the same user.
        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.executeUpdate()).thenThrow(new SQLIntegrityConstraintViolationException("duplicate"));
        assertFalse(new BandejaAvisos(base(statement)).anotar(7, 42));
    }

    @Test
    public void testUnaBaseCaidaNoParaElAviso() throws Exception {
        // The inbox is written in the middle of sending the notification: if it
        // threw, the alert would not go out.
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));
        assertFalse(new BandejaAvisos(dataSource).anotar(7, 42));
    }

}
