package org.apache.shardingsphere.example.sharding.spring.boot.starter.jdbc.repository;

import com.arjuna.ats.jta.TransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import lombok.var;
import org.apache.shardingsphere.example.sharding.spring.boot.starter.jdbc.TransactionConfiguration;
import org.apache.shardingsphere.example.sharding.spring.boot.starter.jdbc.entity.Order;
import org.apache.shardingsphere.transaction.xa.XAShardingSphereTransactionManager;
import org.apache.shardingsphere.transaction.xa.narayana.manager.DataSourceXAResourceRecoveryHelper;
import org.apache.shardingsphere.transaction.xa.narayana.manager.NarayanaXATransactionManagerProvider;
import org.apache.shardingsphere.transaction.xa.spi.XATransactionManagerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import java.sql.*;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public final class OrderRepository {
    
    private final DataSource dataSource;

    public OrderRepository(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderRepository.class);

    public void createTableIfNotExists() throws SQLException {
        String sql = "CREATE TABLE T_ORDER" +
                "(ORDER_ID BIGINT NOT NULL, " +
                "ORDER_TYPE INTEGER, " +
                "USER_ID INTEGER NOT NULL, " +
                "ADDRESS_ID BIGINT NOT NULL, " +
                "STATUS VARCHAR(50), " +
                "PRIMARY KEY (ORDER_ID))";

//        long startTime = System.nanoTime();
//        StopWatch stopWatch = new StopWatch();
//        stopWatch.start();


        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
             statement.executeUpdate(sql);

        } catch (Exception ex) {
            LOGGER.error(ex.getMessage(), ex);
        }

//        long endTime = System.nanoTime();
//        long duration = (endTime - startTime) / 1_000_000;
//        LOGGER.info("Table creation took " + duration + " ms");
//        stopWatch.stop();
//        LOGGER.info("Table creation took STOPWATCH" + stopWatch.getTotalTimeMillis() + " ms");
    }

    public void insertTwoOrdersWithConsistencyViolation() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            // Вставляем корректный заказ (должен попасть на shard0)
            try (PreparedStatement ps1 = connection.prepareStatement(
                    "INSERT INTO T_ORDER (ORDER_ID, USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?, ?)")) {

                ps1.setLong(1, 6000L);
                ps1.setInt(2, 1);
                ps1.setInt(3, 1);
                ps1.setLong(4, 100);
                ps1.setString(5, "INIT");  // допустимое значение

                ps1.executeUpdate();
            }

            // Вставляем некорректный заказ (должен попасть на shard1)
            try (PreparedStatement ps2 = connection.prepareStatement(
                    "INSERT INTO T_ORDER (ORDER_ID, USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?, ?)")) {

                ps2.setLong(1, 6001L);
                ps2.setInt(2, 2);
                ps2.setInt(3, 2);
                ps2.setLong(4, 200);
                ps2.setString(5, "BROKEN");  // нарушает CHECK constraint

                ps2.executeUpdate();
            }

            // Commit — если дошли сюда без ошибки (но не дойдём!)
            connection.commit();
        }
    }

    public void createTableWithCheckConstraint() throws SQLException {
        String sql = "CREATE TABLE T_ORDER" +
                "(ORDER_ID BIGINT NOT NULL, " +
                "ORDER_TYPE INTEGER, " +
                "USER_ID INTEGER NOT NULL, " +
                "ADDRESS_ID BIGINT NOT NULL, " +
                "STATUS VARCHAR(50) NOT NULL CHECK (STATUS IN ('INIT', 'PROCESSING', 'COMPLETED')), " +
                "PRIMARY KEY (ORDER_ID))";

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (Exception ex) {
            LOGGER.error("Error creating table with CHECK constraint: {}", ex.getMessage(), ex);
        }
    }



    public void createTableIfNotExistsWithOffAutoCommit() throws SQLException {
        String sql = "CREATE TABLE T_ORDER" +
                "(ORDER_ID BIGINT NOT NULL, " +
                "ORDER_TYPE INTEGER, " +
                "USER_ID INTEGER NOT NULL, " +
                "ADDRESS_ID BIGINT NOT NULL, " +
                "STATUS VARCHAR(50), " +
                "PRIMARY KEY (ORDER_ID))";

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                LOGGER.error("Failed to create table", ex);
                throw ex;
            }
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ex) {
                    LOGGER.error("Failed to close connection", ex);
                }
            }
        }
    }


    public void executeTransaction() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            // Отключаем автоматическую фиксацию для DML-операций
            connection.setAutoCommit(false);

            try {
                // Вставляем данные в таблицу
                String insertSql = "INSERT INTO T_ORDER (ORDER_ID, ORDER_TYPE, USER_ID, ADDRESS_ID, STATUS) " +
                        "VALUES (1, 2, 1, 123, 'PENDING')";
                statement.executeUpdate(insertSql);

                connection.commit();

//                // Обновляем данные
//                String updateSql = "UPDATE T_ORDER SET STATUS = 'COMPLETED' WHERE ORDER_ID = 1";
//                statement.executeUpdate(updateSql);
//
//                // Фиксация транзакции
//                connection.commit();

            } catch (Exception ex) {
                // Откат транзакции в случае ошибки
                connection.rollback();
                LOGGER.error("Transaction failed: " + ex.getMessage(), ex);
            } finally {
                // Включаем автоматическую фиксацию обратно
                connection.setAutoCommit(true);
            }

        } catch (Exception ex) {
            LOGGER.error("Connection failed: " + ex.getMessage(), ex);
        }
    }


    public void dropTable() throws SQLException {
        // todo fix in shadow
        String sql = "DROP TABLE T_ORDER";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage(), ex);
        }
    }

    public void truncateTable() throws SQLException {
        // String sql = "TRUNCATE TABLE t_order";
        String sql = "DELETE FROM T_ORDER";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            //connection.setAutoCommit(false);
            // statement.getConnection().prepareStatement(sql);\
            statement.executeUpdate(sql);
            //connection.commit();
        }
    }

    public Long insert(final Order order) throws SQLException {
        String sql = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            preparedStatement.setInt(1, order.getUserId());
            preparedStatement.setInt(2, order.getOrderType());
            preparedStatement.setLong(3, order.getAddressId());
            preparedStatement.setString(4, order.getStatus());
            preparedStatement.executeUpdate();
            try (ResultSet resultSet = preparedStatement.getGeneratedKeys()) {
                if (resultSet.next()) {
                    order.setOrderId(resultSet.getLong(1));
                }
            }
        }
        return order.getOrderId();
    }

    public void insertWithoutAutoCommit(Order order) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO T_ORDER (ORDER_ID, USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, order.getOrderId());
            ps.setInt(2, order.getUserId());
            ps.setInt(3, order.getOrderType());
            ps.setLong(4, order.getAddressId());
            ps.setString(5, order.getStatus());
            ps.executeUpdate();
        }
    }


    public Long insertWithoutAutoCommit1(final Order order) throws SQLException {
        String sql = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?)";
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;

        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false); // Выключаем autocommit

            preparedStatement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            preparedStatement.setInt(1, order.getUserId());
            preparedStatement.setInt(2, order.getOrderType());
            preparedStatement.setLong(3, order.getAddressId());
            preparedStatement.setString(4, order.getStatus());

            preparedStatement.executeUpdate();

            resultSet = preparedStatement.getGeneratedKeys();
            if (resultSet.next()) {
                order.setOrderId(resultSet.getLong(1));
            }

            LOGGER.info("Before commit, sleeping...");
            Thread.sleep(10000);
            connection.commit();
            LOGGER.info("After commit");

        } catch (SQLException e) {
            if (connection != null) {
                connection.rollback(); // В случае ошибки откатываем транзакцию
            }
            throw e; // Пробрасываем исключение
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }
            if (preparedStatement != null) {
                preparedStatement.close();
            }
            if (connection != null) {
                try {
                    connection.setAutoCommit(true); // Восстанавливаем autocommit
                    connection.close();
                } catch (SQLException e) {
                    LOGGER.error("Failed to close connection", e);
                }
            }
        }

        return order.getOrderId();
    }

    public DataSource getDataSource() {
        return dataSource;
    }


    public void insertTwoOrders() throws SQLException, InterruptedException {
        LOGGER.info("-------------- Insert Two Orders Begin (XA) ---------------");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement ps1 = connection.prepareStatement("INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?)");
                 PreparedStatement ps2 = connection.prepareStatement("INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?)")) {

                ps1.setInt(1, 1); // ds_1
                ps1.setInt(2, 1);
                ps1.setLong(3, 111);
                ps1.setString(4, "NODE1_INSERT_TEST");

                ps2.setInt(1, 2); // ds_0
                ps2.setInt(2, 2);
                ps2.setLong(3, 222);
                ps2.setString(4, "NODE2_INSERT_TEST");

                ps1.executeUpdate();
                ps2.executeUpdate();

                LOGGER.info("Before commit, sleeping...");
                Thread.sleep(20000);

                connection.commit();
                LOGGER.info("After commit");
            } catch (Exception ex) {
                LOGGER.error("Exception during insertTwoOrders (XA), performing rollback:", ex);
                try {
                    connection.rollback();
                } catch (Exception rollbackEx) {
                    LOGGER.error("Rollback failed:", rollbackEx);
                }
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (Exception autoCommitEx) {
                    LOGGER.error("Failed to reset autoCommit:", autoCommitEx);
                }
            }
        } catch (Exception outerEx) {
            LOGGER.error("Exception getting connection for insertTwoOrders:", outerEx);
        }

        LOGGER.info("-------------- Insert Two Orders Finish (XA) ---------------");
    }

    public void updateOrderStatusWithoutCommit(long orderId, String status, Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE T_ORDER SET STATUS = ? WHERE ORDER_ID = ?")) {
            ps.setString(1, status);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        }
    }

    public String selectOrderStatus(long orderId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT STATUS FROM T_ORDER WHERE ORDER_ID = ?")) {
            ps.setLong(1, orderId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("STATUS");
                }
                return null;
            }
        }
    }
    public String selectOrderStatus(long orderId, Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT STATUS FROM T_ORDER WHERE ORDER_ID = ?")) {
            ps.setLong(1, orderId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("STATUS");
                }
                return null;
            }
        }
    }

    public Long insertTest(final Order order) throws SQLException {
        String sql = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?)";
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;

        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false); // Отключаем autocommit
            preparedStatement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);

            // Устанавливаем параметры запроса
            preparedStatement.setInt(1, order.getUserId());
            preparedStatement.setInt(2, order.getOrderType());
            preparedStatement.setLong(3, order.getAddressId());
            preparedStatement.setString(4, order.getStatus());

            // Выполняем запрос
            preparedStatement.executeUpdate();

            // Получаем сгенерированные ключи
            resultSet = preparedStatement.getGeneratedKeys();
            if (resultSet.next()) {
                order.setOrderId(resultSet.getLong(1));
            }

            connection.commit(); // Фиксируем транзакцию
        } catch (SQLException e) {
            // В случае ошибки откатываем транзакцию
            if (connection != null) {
                connection.rollback();
            }
            throw e; // Пробрасываем исключение дальше
        } finally {
            // Закрываем ресурсы
            if (resultSet != null) {
                resultSet.close();
            }
            if (preparedStatement != null) {
                preparedStatement.close();
            }
            if (connection != null) {
                try {
                    connection.setAutoCommit(true); // Восстанавливаем autocommit
                    connection.close(); // Закрываем соединение
                } catch (SQLException e) {
                    // Логируем ошибку, если не удалось закрыть соединение
                    LOGGER.error("Failed to close connection", e);
                }
            }
        }

        return order.getOrderId();
    }

    public void insertTestWithoutAutoCommit() throws SQLException {
        String sql1 = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (123, 1, 456, 'PENDING')";
//        String sql2 = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (555, 555, 555, '555')";
        Connection connection = null;

        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            Statement statement = null;
            try {
                statement = connection.createStatement();
                statement.executeUpdate(sql1);
                connection.commit();
//                statement.executeUpdate(sql2);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            // throw new SQLException("Искусственная ошибка для проверки отката транзакции");
            connection.commit();
        } catch (SQLException e) {
            if (connection != null) {
                connection.rollback();
            }
            throw e;
        } finally {
            if (connection != null) {
                try {
                    connection.setAutoCommit(true);
                    connection.close();
                } catch (SQLException e) {
                    LOGGER.error("Failed to close connection", e);
                }
            }
        }
    }

    public void DoubleInsertTestWithoutAutoCommit() throws SQLException {
        String sql1 = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (123, 1, 456, 'PENDING')";
        String sql2 = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (789, 2, 101, 'PROCESSING')";
        Connection connection = null;

        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            Statement statement = null;
            try {
                statement = connection.createStatement();
                statement.executeUpdate(sql1);
                statement.executeUpdate(sql2);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            connection.commit();
        } catch (SQLException e) {
            if (connection != null) {
                connection.rollback();
            }
            throw e;
        } finally {
            if (connection != null) {
                try {
                    connection.setAutoCommit(true);
                    connection.close();
                } catch (SQLException e) {
                    LOGGER.error("Failed to close connection", e);
                }
            }
        }
    }


    public void TestConnection() throws SQLException {
        String sql = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (123, 1, 456, 'PENDING')";
        Connection connection = null;

        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void insertTest2WithAutoCommit() throws SQLException {
        String sql = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (123, 1, 456, 'PENDING')";
        Connection connection = null;

        try {
            connection = dataSource.getConnection();
            Statement statement = null;
            try {
                statement = connection.createStatement();
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

//    public Long insert(final Order order) throws SQLException {
//        String sql = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?)";
//        Connection connection = dataSource.getConnection();
//        try {
//            connection.setAutoCommit(false);
//
//            try (PreparedStatement preparedStatement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
//                preparedStatement.setInt(1, order.getUserId());
//                preparedStatement.setInt(2, order.getOrderType());
//                preparedStatement.setLong(3, order.getAddressId());
//                preparedStatement.setString(4, order.getStatus());
//                preparedStatement.executeUpdate();
//
//                try (ResultSet resultSet = preparedStatement.getGeneratedKeys()) {
//                    if (resultSet.next()) {
//                        order.setOrderId(resultSet.getLong(1));
//                    }
//                }
//
//                connection.commit();
//            } catch (SQLException e) {
//                connection.rollback();
//                throw e;
//            }
//        } finally {
//            connection.setAutoCommit(true);
//            connection.close();
//        }
//        return order.getOrderId();
//    }

    public void insertTest() throws SQLException {
        String sql1 = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (4, 3, 2, 1)";
        String sql2 = "INSERT INTO T_ORDER (USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (1, 2, 3, 4)";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql1);
                statement.executeUpdate(sql2);

                throw new SQLException("Simulated error");
            }

        } catch (SQLException e) {
            System.err.println("Transaction failed: " + e.getMessage());
        }
    }

    public void delete(final Long orderId) throws SQLException {
        String sql = "DELETE FROM T_ORDER WHERE ORDER_ID=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setLong(1, orderId);
            preparedStatement.executeUpdate();
        }
    }

    public List<Order> selectAll() throws SQLException {
        return getOrders("SELECT * FROM T_ORDER");
    }
    
   private List<Order> getOrders(final String sql) throws SQLException {
        List<Order> result = new LinkedList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                Order order = new Order();
                order.setOrderId(resultSet.getLong(1));
                order.setOrderType(resultSet.getInt(2));
                order.setUserId(resultSet.getInt(3));
                order.setAddressId(resultSet.getLong(4));
                order.setStatus(resultSet.getString(5));
                result.add(order);
            }
        }
        return result;
    }

    public void TestTransaction() throws SQLException {
        String sql = "CREATE TABLE TEST" +
                "(ORDER_ID BIGINT NOT NULL, " +
                "ORDER_TYPE INTEGER, " +
                "USER_ID INTEGER NOT NULL, " +
                "ADDRESS_ID BIGINT NOT NULL, " +
                "STATUS VARCHAR(50), " +
                "PRIMARY KEY (ORDER_ID))";

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            // Отключаем автоматическую фиксацию транзакций
            connection.setAutoCommit(false);

            try {
                // Создаем таблицу
                statement.executeUpdate(sql);

//                // Вставляем данные во все столбцы
//                String insertSql = "INSERT INTO TEST (ORDER_ID, ORDER_TYPE, USER_ID, ADDRESS_ID, STATUS) " +
//                        "VALUES (1, 2, 1, 123, 'PENDING')";
//                statement.executeUpdate(insertSql);
//
//                // Имитация ошибки (раскомментируйте для тестирования отката транзакции)
//                // throw new RuntimeException("Ошибка во вложенной транзакции");
//
//                // Продолжение основной транзакции
//                String updateSql = "UPDATE TEST SET STATUS = 'COMPLETED' WHERE ORDER_ID = 1";
//                statement.executeUpdate(updateSql);

                // Фиксация транзакции
                connection.commit();
            } catch (Exception ex) {
                // Откат транзакции в случае ошибки
                connection.rollback();
                LOGGER.error(ex.getMessage(), ex);
            } finally {
                // Включаем автоматическую фиксацию транзакций обратно
                connection.setAutoCommit(true);
            }

        } catch (Exception ex) {
            LOGGER.error(ex.getMessage(), ex);
        }
    }
}
