package gq.luma.bot.services;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class DatabasePinRecordsTest {
    @Test
    void failedLookupDoesNotReportMessageAsUnpinned() throws Exception {
        Database database = databaseWithBrokenStatement("getPinNotificationByPinnedMessage");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> database.isPinnedMessageNotified(10L));
        assertInstanceOf(SQLException.class, failure.getCause());
    }

    @Test
    void failedSaveIsReportedToPinService() throws Exception {
        Database database = databaseWithBrokenStatement("insertPinNotification");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> database.createPinNotification(10L, 1010L));
        assertInstanceOf(SQLException.class, failure.getCause());
    }

    private Database databaseWithBrokenStatement(String fieldName) throws Exception {
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("execute")) {
                        throw new SQLException("Simulated connection failure");
                    }
                    return null;
                });
        Database database = new Database();
        Field field = Database.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(database, statement);
        return database;
    }
}
