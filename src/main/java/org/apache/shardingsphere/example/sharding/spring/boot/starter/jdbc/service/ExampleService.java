package org.apache.shardingsphere.example.sharding.spring.boot.starter.jdbc.service;

import org.apache.shardingsphere.example.sharding.spring.boot.starter.jdbc.entity.Order;
import org.apache.shardingsphere.example.sharding.spring.boot.starter.jdbc.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public final class ExampleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExampleService.class);

    private final OrderRepository orderRepository;

    private PrintWriter resultsWriter;
    int updateIterations;
    int deleteIterations;
    int truncateIterations;

    public ExampleService(final DataSource dataSource) {
        orderRepository = new OrderRepository(dataSource);
    }


    public void run() throws SQLException, InterruptedException {
        try {
            this.resultsWriter = new PrintWriter("performance_results.txt");
            this.initEnvironment();
            // this.testAtomicity();
            // this.testLostUpdate();
            // this.testConsistencyViolation();
           // this.testSingleInsertPerformance(10000);
            this.testPerformance(
                    1000,  // SINGLE INSERT
                    100000,  // BATCH INSERT
                    100,    // batch size
                    10,      // numIterations ? UPDATE/DELETE iterations
                    10000,   // recordsPerIteration ? по 2000 записей на итерацию
                    1       // truncate iterations
            );
            // this.testBatchInsertPerformance(10,10000);
        } catch (Exception e) {
            LOGGER.error("", e);
            throw new RuntimeException(e);
        } finally {
            if (this.resultsWriter != null) {
                this.resultsWriter.close();
            }
            this.cleanEnvironment();
        }
    }

    private void logAndWrite(String format, Object... args) {
        String line = String.format(format, args);
        LOGGER.info(line);
        if (resultsWriter != null) {
            resultsWriter.println(line);
            resultsWriter.flush();
        }
    }

    private long testSingleInsertPerformance(int numRecords, long baseOrderId) throws SQLException {
        orderRepository.truncateTable();

        long startInsert = System.currentTimeMillis();

        for (int i = 0; i < numRecords; i++) {
            try (Connection connection = orderRepository.getDataSource().getConnection()) {
                connection.setAutoCommit(false);

                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO T_ORDER (ORDER_ID, USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?, ?)")) {

                    long orderId = baseOrderId + i;

                    ps.setLong(1, orderId);
                    ps.setInt(2, i % 10);
                    ps.setInt(3, i % 5);
                    ps.setLong(4, 400 + (i % 50));
                    ps.setString(5, "INIT");

                    ps.executeUpdate();
                    connection.commit();
                }
            }
        }

        long endInsert = System.currentTimeMillis();
        return endInsert - startInsert;
    }

    private long testBatchInsertPerformance(int batchSize, int numRecords, long baseOrderId) throws SQLException {
        orderRepository.truncateTable();

        long startInsert = System.currentTimeMillis();

        try (Connection connection = orderRepository.getDataSource().getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO T_ORDER (ORDER_ID, USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?, ?)")) {

                for (int i = 0; i < numRecords; i++) {
                    long orderId = baseOrderId + i;

                    ps.setLong(1, orderId);
                    ps.setInt(2, i % 10);
                    ps.setInt(3, i % 5);
                    ps.setLong(4, 500 + (i % 50));
                    ps.setString(5, "INIT");

                    ps.addBatch();

                    if ((i + 1) % batchSize == 0) {
                        ps.executeBatch();
                    }
                }

                ps.executeBatch();
                connection.commit();
            }
        }

        long endInsert = System.currentTimeMillis();
        return endInsert - startInsert;
    }

    private void prepareRecordsForIterations(int recordsPerIteration, int numIterations, long baseOrderId) throws SQLException {
        try (Connection connection = orderRepository.getDataSource().getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO T_ORDER (ORDER_ID, USER_ID, ORDER_TYPE, ADDRESS_ID, STATUS) VALUES (?, ?, ?, ?, ?)")) {

                for (int iteration = 1; iteration <= numIterations; iteration++) {
                    String status = "UPDATE" + iteration;

                    for (int i = 0; i < recordsPerIteration; i++) {
                        long orderId = baseOrderId + (iteration * 100000L) + i; // уникальные ID на каждую итерацию

                        ps.setLong(1, orderId);
                        ps.setInt(2, i % 10);
                        ps.setInt(3, i % 5);
                        ps.setLong(4, 800 + (i % 50));
                        ps.setString(5, status);

                        ps.addBatch();
                    }
                }

                ps.executeBatch();
                connection.commit();
            }
        }
    }


    public void testPerformance(
            int numSingleInsertRecords,
            int numBatchInsertRecords,
            int batchSize,
            int numIterations,
            int recordsPerIteration,
            int truncateIterations
    ) throws SQLException {

        logAndWrite("======================================================================");
        logAndWrite("==============   PERFORMANCE TEST  ==============");
        logAndWrite("======================================================================");

        // === SINGLE INSERT ===
        logAndWrite("---- SINGLE INSERT START ----");
        long singleInsertTime = testSingleInsertPerformance(numSingleInsertRecords, 200000L);
        logAndWrite("SINGLE INSERT of %d records took %d ms", numSingleInsertRecords, singleInsertTime);
        logAndWrite("---- SINGLE INSERT END ----");

        // === BATCH INSERT ===
        logAndWrite("---- BATCH INSERT START ----");
        long batchInsertTime = testBatchInsertPerformance(batchSize, numBatchInsertRecords, 300000L);
        logAndWrite("BATCH INSERT of %d records (batch size %d) took %d ms", numBatchInsertRecords, batchSize, batchInsertTime);
        logAndWrite("---- BATCH INSERT END ----");

        // === ПОДГОТОВКА ДАННЫХ ДЛЯ ITERATIVE UPDATE/DELETE ===
        logAndWrite("---- PREPARING RECORDS FOR ITERATIVE UPDATE/DELETE ----");
        prepareRecordsForIterations(recordsPerIteration, numIterations, 400000L);
        logAndWrite("Prepared %d records per iteration for %d iterations", recordsPerIteration, numIterations);

        // === UPDATE итерации ===
        long totalUpdateTime = 0;
        for (int i = 1; i <= numIterations; i++) {
            logAndWrite("UPDATE iteration %d/%d", i, numIterations);

            long startUpdate = System.currentTimeMillis();

            try (Connection connection = orderRepository.getDataSource().getConnection()) {
                connection.setAutoCommit(false);

                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE T_ORDER SET STATUS = ? WHERE STATUS = ?")) {

                    String targetStatus = "PROCESSING" + i;
                    String currentStatus = "UPDATE" + i;

                    ps.setString(1, targetStatus);
                    ps.setString(2, currentStatus);

                    int updatedRows = ps.executeUpdate();
                    logAndWrite("Updated %d rows", updatedRows);

                    connection.commit();
                }
            }

            long endUpdate = System.currentTimeMillis();
            long updateTime = endUpdate - startUpdate;
            logAndWrite("UPDATE iteration %d took %d ms", i, updateTime);
            totalUpdateTime += updateTime;
        }

        // === DELETE итерации ===
        long totalDeleteTime = 0;
        for (int i = 1; i <= numIterations; i++) {
            logAndWrite("DELETE iteration %d/%d", i, numIterations);

            long startDelete = System.currentTimeMillis();

            try (Connection connection = orderRepository.getDataSource().getConnection()) {
                connection.setAutoCommit(false);

                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM T_ORDER WHERE STATUS = ?")) {

                    String deleteStatus = "PROCESSING" + i;
                    ps.setString(1, deleteStatus);

                    int deletedRows = ps.executeUpdate();
                    logAndWrite("Deleted %d rows", deletedRows);

                    connection.commit();
                }
            }

            long endDelete = System.currentTimeMillis();
            long deleteTime = endDelete - startDelete;
            logAndWrite("DELETE iteration %d took %d ms", i, deleteTime);
            totalDeleteTime += deleteTime;
        }

        // === TRUNCATE итерации ===
        long totalTruncateTime = 0;
        for (int i = 0; i < truncateIterations; i++) {
            logAndWrite("DELETE iteration %d/%d", i + 1, truncateIterations);

            long startTruncate = System.currentTimeMillis();

            orderRepository.truncateTable();

            long endTruncate = System.currentTimeMillis();
            long truncateTime = endTruncate - startTruncate;
            logAndWrite("DELETE iteration %d took %d ms", i + 1, truncateTime);
            totalTruncateTime += truncateTime;
        }

        // === СВОДКА ===
        logAndWrite("======================================================================");
        logAndWrite("FULL PERFORMANCE SUMMARY:");
        logAndWrite("SINGLE INSERT: %d ms", singleInsertTime);
        logAndWrite("BATCH INSERT: %d ms", batchInsertTime);
        logAndWrite("TOTAL UPDATE TIME (%d iterations): %d ms", numIterations, totalUpdateTime);
        logAndWrite("TOTAL DELETE TIME (%d iterations): %d ms", numIterations, totalDeleteTime);
        logAndWrite("TOTAL DELETE TIME (%d iterations): %d ms", truncateIterations, totalTruncateTime);
        logAndWrite("======================================================================");
    }

    public void testLostUpdate() throws SQLException, InterruptedException {
        LOGGER.info("======================================================================");
        LOGGER.info("==============           LOST UPDATE TEST START              ==============");
        LOGGER.info("======================================================================");

        // Подготовим тестовые данные
        long testOrderId = 12345L;

        LOGGER.info("Inserting test order...");
        Order order = new Order();
        order.setOrderId(12345L); // ВАЖНО ? фиксированный ORDER_ID
        order.setUserId(1);
        order.setOrderType(1);
        order.setAddressId(100);
        order.setStatus("INIT");
        orderRepository.insertWithoutAutoCommit(order);

        LOGGER.info("Inserted order ID={} with STATUS=INIT", testOrderId);

        // Поток 1
        Thread t1 = new Thread(() -> {
            try (Connection conn = orderRepository.getDataSource().getConnection()) {
                conn.setAutoCommit(false);
                LOGGER.info("T1: Reading STATUS...");
                String status = orderRepository.selectOrderStatus(testOrderId, conn);
                LOGGER.info("T1: STATUS read = {}", status);

                LOGGER.info("T1: Updating STATUS to 'T1_UPDATE'...");
                orderRepository.updateOrderStatusWithoutCommit(testOrderId, "T1_UPDATE", conn);

                Thread.sleep(3000); // ждём, чтобы второй поток начал

                LOGGER.info("T1: Committing...");
                conn.commit();
                LOGGER.info("T1: Committed.");
            } catch (Exception e) {
                LOGGER.error("T1 error:", e);
            }
        });

        // Поток 2
        Thread t2 = new Thread(() -> {
            try (Connection conn = orderRepository.getDataSource().getConnection()) {
                conn.setAutoCommit(false);
                LOGGER.info("T2: Reading STATUS...");
                String status = orderRepository.selectOrderStatus(testOrderId, conn);
                LOGGER.info("T2: STATUS read = {}", status);

                LOGGER.info("T2: Updating STATUS to 'T2_UPDATE'...");
                orderRepository.updateOrderStatusWithoutCommit(testOrderId, "T2_UPDATE", conn);

                Thread.sleep(1000); // чуть меньше ждём

                LOGGER.info("T2: Committing...");
                conn.commit();
                LOGGER.info("T2: Committed.");
            } catch (Exception e) {
                LOGGER.error("T2 error:", e);
            }
        });

        // Запускаем потоки
        t1.start();
        Thread.sleep(500); // даём T1 немного форы
        t2.start();

        // Ждём завершения потоков
        t1.join();
        t2.join();

        // Финальный SELECT
        LOGGER.info("Final SELECT after both transactions:");
        String finalStatus = orderRepository.selectOrderStatus(testOrderId);
        LOGGER.info("Final STATUS = {}", finalStatus);

        LOGGER.info("======================================================================");
        LOGGER.info("==============           LOST UPDATE TEST END                ==============");
        LOGGER.info("======================================================================");
    }

    public void testAtomicity() throws SQLException, InterruptedException {
        LOGGER.info("======================================================================");
        LOGGER.info("==============           ATOMICITY TEST START              ==============");
        LOGGER.info("======================================================================");

        LOGGER.info("\n\n======================== [1] SELECT BEFORE INSERT ========================");
        safePrintData();

        LOGGER.info("\n\n======================== [2] INSERT TWO ORDERS (XA) ======================");
        try {
            orderRepository.insertTwoOrders();
        } catch (Exception e) {
            LOGGER.error("Exception during insertTwoOrders (XA):", e);
        }
        Thread.sleep(10000); // Даём больше времени pool-у

        LOGGER.info("Forcing pool validation query to refresh broken connections...");
        boolean pingSuccess = false;
        for (int i = 0; i < 3 && !pingSuccess; i++) {
            try (Connection conn = orderRepository.getDataSource().getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT 1")) {
                stmt.executeQuery();
                pingSuccess = true;
                LOGGER.info("Ping successful on attempt {}", i + 1);
            } catch (Exception e) {
                LOGGER.error("Ping failed on attempt {}: {}", i + 1, e.getMessage());
                Thread.sleep(3000);
            }
        }

        LOGGER.info("\n\n======================== [3] SELECT AFTER INSERT ========================");
        safePrintData();

        LOGGER.info("======================================================================");
        LOGGER.info("==============            ATOMICITY TEST END                ==============");
        LOGGER.info("======================================================================");
    }


    public void testConsistencyViolation() throws SQLException {
        LOGGER.info("======================================================================");
        LOGGER.info("==============           CONSISTENCY TEST START              ==============");
        LOGGER.info("======================================================================");

        // Пересоздадим таблицу с CHECK constraint
        LOGGER.info("Recreating table with CHECK constraint...");
        orderRepository.dropTable();
        orderRepository.createTableWithCheckConstraint();

        // Попытаемся вставить 2 заказа (один OK, один BROKEN)
        try {
            orderRepository.insertTwoOrdersWithConsistencyViolation();
            LOGGER.warn("ERROR: Both inserts succeeded — this should not happen!");
        } catch (SQLException ex) {
            LOGGER.info("Expected exception: {}", ex.getMessage());
            LOGGER.info("Rollback should happen automatically.");
        }

        // Проверим таблицу
        LOGGER.info("Final SELECT to verify table consistency:");
        List<Order> allOrders = orderRepository.selectAll();
        if (allOrders.isEmpty()) {
            LOGGER.info("RESULT: Consistency preserved — table is empty.");
        } else {
            LOGGER.warn("RESULT: Consistency VIOLATED — found rows: {}", allOrders.size());
            for (Order order : allOrders) {
                LOGGER.warn(order.toString());
            }
        }

        LOGGER.info("======================================================================");
        LOGGER.info("==============           CONSISTENCY TEST END                ==============");
        LOGGER.info("======================================================================");
    }


    private void initEnvironment() throws SQLException {
        orderRepository.createTableIfNotExists();
    }

    private void testInsertwithoutAutoCommit() throws SQLException {
        orderRepository.insertTestWithoutAutoCommit();
    }

    private void processSuccess() throws SQLException {
        LOGGER.info("-------------- Process Success Begin ---------------");
        List<Long> orderIds = insertData();
        printData();
//        deleteData(orderIds);
//        printData();
        LOGGER.info("-------------- Process Success Finish --------------");
    }

    private List<Long> insertData() throws SQLException {
        LOGGER.info("---------------------------- Insert Data ----------------------------");
        List<Long> result = new ArrayList<>(10);
        for (int i = 1; i <= 2; i++) {
            Order order = new Order();
            order.setUserId(i);
            order.setOrderType(i % 2);
            order.setAddressId(i);
            order.setStatus("INSERT_TEST");
            orderRepository.insertWithoutAutoCommit(order);

            result.add(order.getOrderId());
        }
        return result;
    }

    private void safePrintData() {
        LOGGER.info("======================================================================");
        LOGGER.info(">>> PRINT ORDER DATA START <<<");
        LOGGER.info("----------------------------------------------------------------------");

        try {
            for (Order each : this.selectAll()) {
                LOGGER.info(each.toString());
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to SELECT ORDER DATA: ", e);
        }

        LOGGER.info("----------------------------------------------------------------------");
        LOGGER.info(">>> PRINT ORDER DATA END <<<");
        LOGGER.info("======================================================================");
    }

    private void deleteData(final List<Long> orderIds) throws SQLException {
        LOGGER.info("---------------------------- Delete Data ----------------------------");
        for (Long each : orderIds) {
            System.out.println(each);
            orderRepository.delete(each);
        }
    }

    private void printData() throws SQLException {
        LOGGER.info("======================================================================");
        LOGGER.info(">>> PRINT ORDER DATA START <<<");
        LOGGER.info("----------------------------------------------------------------------");

        for (Order each : this.selectAll()) {
            LOGGER.info(each.toString());
        }

        LOGGER.info("----------------------------------------------------------------------");
        LOGGER.info(">>> PRINT ORDER DATA END <<<");
        LOGGER.info("======================================================================");
    }

    private List<Order> selectAll() throws SQLException {
        return orderRepository.selectAll();
    }

    private void cleanEnvironment() throws SQLException {
        orderRepository.dropTable();
    }
}
