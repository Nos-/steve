package de.rwth.idsg.steve.repository.impl;

import de.rwth.idsg.steve.repository.OcppServerRepository;
import de.rwth.idsg.steve.repository.ReservationRepository;
import de.rwth.idsg.steve.repository.dto.InsertConnectorStatusParams;
import de.rwth.idsg.steve.repository.dto.InsertTransactionParams;
import de.rwth.idsg.steve.repository.dto.TransactionStatusUpdate;
import de.rwth.idsg.steve.repository.dto.UpdateChargeboxParams;
import de.rwth.idsg.steve.repository.dto.UpdateTransactionParams;
import de.rwth.idsg.steve.utils.CustomDSL;
import lombok.extern.slf4j.Slf4j;
import ocpp.cs._2012._06.Location;
import ocpp.cs._2012._06.Measurand;
import ocpp.cs._2012._06.MeterValue;
import ocpp.cs._2012._06.ReadingContext;
import ocpp.cs._2012._06.UnitOfMeasure;
import ocpp.cs._2012._06.ValueFormat;
import org.joda.time.DateTime;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.SelectConditionStep;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;

import static jooq.steve.db.tables.ChargeBox.CHARGE_BOX;
import static jooq.steve.db.tables.Connector.CONNECTOR;
import static jooq.steve.db.tables.ConnectorMeterValue.CONNECTOR_METER_VALUE;
import static jooq.steve.db.tables.ConnectorStatus.CONNECTOR_STATUS;
import static jooq.steve.db.tables.Transaction.TRANSACTION;

/**
 * This class has methods for database access that are used by the OCPP service.
 *
 * http://www.jooq.org/doc/3.4/manual/sql-execution/transaction-management/
 *
 * @author Sevket Goekay <goekay@dbis.rwth-aachen.de>
 *
 */
@Slf4j
@Repository
public class OcppServerRepositoryImpl implements OcppServerRepository {

    @Autowired private DSLContext ctx;
    @Autowired private ReservationRepository reservationRepository;

    @Override
    public boolean updateChargebox(UpdateChargeboxParams p) {
        int count = ctx.update(CHARGE_BOX)
                       .set(CHARGE_BOX.OCPP_PROTOCOL, p.getOcppProtocol().getCompositeValue())
                       .set(CHARGE_BOX.CHARGE_POINT_VENDOR, p.getVendor())
                       .set(CHARGE_BOX.CHARGE_POINT_MODEL, p.getModel())
                       .set(CHARGE_BOX.CHARGE_POINT_SERIAL_NUMBER, p.getPointSerial())
                       .set(CHARGE_BOX.CHARGE_BOX_SERIAL_NUMBER, p.getBoxSerial())
                       .set(CHARGE_BOX.FW_VERSION, p.getFwVersion())
                       .set(CHARGE_BOX.ICCID, p.getIccid())
                       .set(CHARGE_BOX.IMSI, p.getImsi())
                       .set(CHARGE_BOX.METER_TYPE, p.getMeterType())
                       .set(CHARGE_BOX.METER_SERIAL_NUMBER, p.getMeterSerial())
                       .set(CHARGE_BOX.LAST_HEARTBEAT_TIMESTAMP, p.getHeartbeatTimestamp())
                       .where(CHARGE_BOX.CHARGE_BOX_ID.equal(p.getChargeBoxId()))
                       .execute();

        boolean isRegistered = false;

        if (count == 1) {
            log.info("The chargebox '{}' is registered and its boot acknowledged.", p.getChargeBoxId());
            isRegistered = true;
        } else {
            log.error("The chargebox '{}' is NOT registered and its boot NOT acknowledged.", p.getChargeBoxId());
        }
        return isRegistered;
    }

    @Override
    public void updateEndpointAddress(String chargeBoxIdentity, String endpointAddress) {
        ctx.update(CHARGE_BOX)
           .set(CHARGE_BOX.ENDPOINT_ADDRESS, endpointAddress)
           .where(CHARGE_BOX.CHARGE_BOX_ID.equal(chargeBoxIdentity))
           .execute();
    }

    @Override
    public void updateChargeboxFirmwareStatus(String chargeBoxIdentity, String firmwareStatus) {
        ctx.update(CHARGE_BOX)
           .set(CHARGE_BOX.FW_UPDATE_STATUS, firmwareStatus)
           .set(CHARGE_BOX.FW_UPDATE_TIMESTAMP, CustomDSL.utcTimestamp())
           .where(CHARGE_BOX.CHARGE_BOX_ID.equal(chargeBoxIdentity))
           .execute();
    }

    @Override
    public void updateChargeboxDiagnosticsStatus(String chargeBoxIdentity, String status) {
        ctx.update(CHARGE_BOX)
           .set(CHARGE_BOX.DIAGNOSTICS_STATUS, status)
           .set(CHARGE_BOX.DIAGNOSTICS_TIMESTAMP, CustomDSL.utcTimestamp())
           .where(CHARGE_BOX.CHARGE_BOX_ID.equal(chargeBoxIdentity))
           .execute();
    }

    @Override
    public void updateChargeboxHeartbeat(String chargeBoxIdentity, DateTime ts) {
        ctx.update(CHARGE_BOX)
           .set(CHARGE_BOX.LAST_HEARTBEAT_TIMESTAMP, ts)
           .where(CHARGE_BOX.CHARGE_BOX_ID.equal(chargeBoxIdentity))
           .execute();
    }

    @Override
    public void insertConnectorStatus(InsertConnectorStatusParams p) {

        ctx.transaction(configuration -> {
            DSLContext ctx = DSL.using(configuration);

            // Step 1
            insertIgnoreConnector(ctx, p.getChargeBoxId(), p.getConnectorId());

            // -------------------------------------------------------------------------
            // Step 2: We store a log of connector statuses
            // -------------------------------------------------------------------------

            ctx.insertInto(CONNECTOR_STATUS)
               .set(CONNECTOR_STATUS.CONNECTOR_PK, DSL.select(CONNECTOR.CONNECTOR_PK)
                                                      .from(CONNECTOR)
                                                      .where(CONNECTOR.CHARGE_BOX_ID.equal(p.getChargeBoxId()))
                                                      .and(CONNECTOR.CONNECTOR_ID.equal(p.getConnectorId()))
               )
               .set(CONNECTOR_STATUS.STATUS_TIMESTAMP, p.getTimestamp())
               .set(CONNECTOR_STATUS.STATUS, p.getStatus())
               .set(CONNECTOR_STATUS.ERROR_CODE, p.getErrorCode())
               .set(CONNECTOR_STATUS.ERROR_INFO, p.getErrorInfo())
               .set(CONNECTOR_STATUS.VENDOR_ID, p.getVendorId())
               .set(CONNECTOR_STATUS.VENDOR_ERROR_CODE, p.getErrorCode())
               .execute();

            log.debug("Stored a new connector status for {}/{}.", p.getChargeBoxId(), p.getConnectorId());
        });
    }

    @Override
    public void insertMeterValues12(final String chargeBoxIdentity, final int connectorId,
                                    final List<ocpp.cs._2010._08.MeterValue> list) {

        ctx.transaction(configuration -> {
            DSLContext ctx = DSL.using(configuration);

            insertIgnoreConnector(ctx, chargeBoxIdentity, connectorId);
            int connectorPk = getConnectorPkFromConnector(ctx, chargeBoxIdentity, connectorId);
            batchInsertMeterValues12(ctx, list, connectorPk);
        });
    }

    @Override
    public void insertMeterValues15(final String chargeBoxIdentity, final int connectorId,
                                    final List<ocpp.cs._2012._06.MeterValue> list, final Integer transactionId) {

        ctx.transaction(configuration -> {
            DSLContext ctx = DSL.using(configuration);

            insertIgnoreConnector(ctx, chargeBoxIdentity, connectorId);
            int connectorPk = getConnectorPkFromConnector(ctx, chargeBoxIdentity, connectorId);
            batchInsertMeterValues15(ctx, list, connectorPk, transactionId);
        });
    }

    @Override
    public void insertMeterValuesOfTransaction(String chargeBoxIdentity, final int transactionId,
                                               final List<MeterValue> list) {

        ctx.transaction(configuration -> {
            DSLContext ctx = DSL.using(configuration);

            // First, get connector primary key from transaction table
            int connectorPk = ctx.select(TRANSACTION.CONNECTOR_PK)
                                 .from(TRANSACTION)
                                 .where(TRANSACTION.TRANSACTION_PK.equal(transactionId))
                                 .fetchOne()
                                 .value1();

            batchInsertMeterValues15(ctx, list, connectorPk, transactionId);
        });
    }

    @Override
    public Integer insertTransaction(InsertTransactionParams p) {

        return ctx.transactionResult(configuration -> {
            DSLContext ctx = DSL.using(configuration);

            insertIgnoreConnector(ctx, p.getChargeBoxId(), p.getConnectorId());

            SelectConditionStep<Record1<Integer>> connectorPkQuery =
                    DSL.select(CONNECTOR.CONNECTOR_PK)
                       .from(CONNECTOR)
                       .where(CONNECTOR.CHARGE_BOX_ID.equal(p.getChargeBoxId()))
                       .and(CONNECTOR.CONNECTOR_ID.equal(p.getConnectorId()));

            // -------------------------------------------------------------------------
            // Step 1: Insert transaction
            // -------------------------------------------------------------------------

            int transactionId = ctx.insertInto(TRANSACTION)
                                   .set(CONNECTOR_STATUS.CONNECTOR_PK, connectorPkQuery)
                                   .set(TRANSACTION.ID_TAG, p.getIdTag())
                                   .set(TRANSACTION.START_TIMESTAMP, p.getStartTimestamp())
                                   .set(TRANSACTION.START_VALUE, p.getStartMeterValue())
                                   .returning(TRANSACTION.TRANSACTION_PK)
                                   .fetchOne()
                                   .getTransactionPk();

            // -------------------------------------------------------------------------
            // Step 2 for OCPP 1.5: A startTransaction may be related to a reservation
            // -------------------------------------------------------------------------

            if (p.isSetReservationId()) {
                reservationRepository.used(p.getReservationId(), transactionId);
            }

            // -------------------------------------------------------------------------
            // Step 3: Set connector status to "Occupied"
            // -------------------------------------------------------------------------

            insertConnectorStatus(connectorPkQuery, p.getStartTimestamp(), p.getStatusUpdate());

            return transactionId;
        });
    }

    @Override
    public void updateTransaction(UpdateTransactionParams p) {

        // -------------------------------------------------------------------------
        // Step 1: Update transaction table
        //
        // After update, a DB trigger sets the user.inTransaction field to 0
        // -------------------------------------------------------------------------

        ctx.update(TRANSACTION)
           .set(TRANSACTION.STOP_TIMESTAMP, p.getStopTimestamp())
           .set(TRANSACTION.STOP_VALUE, p.getStopMeterValue())
           .where(TRANSACTION.TRANSACTION_PK.equal(p.getTransactionId()))
           .and(TRANSACTION.STOP_TIMESTAMP.isNull())
           .and(TRANSACTION.STOP_VALUE.isNull())
           .execute();

        // -------------------------------------------------------------------------
        // Step 2: Set connector status back to "Available" again
        // -------------------------------------------------------------------------

        SelectConditionStep<Record1<Integer>> connectorPkQuery =
                DSL.select(TRANSACTION.CONNECTOR_PK)
                   .from(TRANSACTION)
                   .where(TRANSACTION.TRANSACTION_PK.equal(p.getTransactionId()));

        insertConnectorStatus(connectorPkQuery, p.getStopTimestamp(), p.getStatusUpdate());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * After a transaction start/stop event, a charging station _might_ send a connector status notification, but it is
     * not required. With this, we make sure that the status is updated accordingly. Since we use the timestamp of the
     * transaction data, we do not necessarily insert a "most recent" status.
     *
     * If the station sends a notification, we will have a more recent timestamp, and therefore the status of the
     * notification will be used as current. Or, if this transaction data was sent to us for a failed push from the past
     * and we have a "more recent" status, it will still be the current status.
     */
    private void insertConnectorStatus(SelectConditionStep<Record1<Integer>> connectorPkQuery,
                                       DateTime timestamp,
                                       TransactionStatusUpdate statusUpdate) {
        ctx.insertInto(CONNECTOR_STATUS)
           .set(CONNECTOR_STATUS.CONNECTOR_PK, connectorPkQuery)
           .set(CONNECTOR_STATUS.STATUS_TIMESTAMP, timestamp)
           .set(CONNECTOR_STATUS.STATUS, statusUpdate.getStatus())
           .set(CONNECTOR_STATUS.ERROR_CODE, statusUpdate.getErrorCode())
           .execute();
    }

    /**
     * If the connector information was not received before, insert it. Otherwise, ignore.
     */
    private void insertIgnoreConnector(DSLContext ctx, String chargeBoxIdentity, int connectorId) {
        int count = ctx.insertInto(CONNECTOR,
                            CONNECTOR.CHARGE_BOX_ID, CONNECTOR.CONNECTOR_ID)
                       .values(chargeBoxIdentity, connectorId)
                       .onDuplicateKeyIgnore() // Important detail
                       .execute();

        if (count == 1) {
            log.info("The connector {}/{} is NEW, and inserted into DB.", chargeBoxIdentity, connectorId);
        }
    }

    private int getConnectorPkFromConnector(DSLContext ctx, String chargeBoxIdentity, int connectorId) {
        return ctx.select(CONNECTOR.CONNECTOR_PK)
                  .from(CONNECTOR)
                  .where(CONNECTOR.CHARGE_BOX_ID.equal(chargeBoxIdentity)
                    .and(CONNECTOR.CONNECTOR_ID.equal(connectorId)))
                  .fetchOne()
                  .value1();
    }

    private void batchInsertMeterValues12(DSLContext ctx, List<ocpp.cs._2010._08.MeterValue> list, int connectorPk) {
        // Init query with DUMMY values. The actual values are not important.
        BatchBindStep batchBindStep = ctx.batch(
                ctx.insertInto(CONNECTOR_METER_VALUE,
                        CONNECTOR_METER_VALUE.CONNECTOR_PK,
                        CONNECTOR_METER_VALUE.VALUE_TIMESTAMP,
                        CONNECTOR_METER_VALUE.VALUE)
                   .values(0, null, null)
        );

        // OCPP 1.2 allows multiple "values" elements
        for (ocpp.cs._2010._08.MeterValue valuesElement : list) {
            DateTime ts = valuesElement.getTimestamp();
            String value = String.valueOf(valuesElement.getValue());

            batchBindStep.bind(connectorPk, ts, value);
        }

        batchBindStep.execute();
    }

    private void batchInsertMeterValues15(DSLContext ctx, List<ocpp.cs._2012._06.MeterValue> list, int connectorPk,
                                          Integer transactionId) {
        // Init query with DUMMY values. The actual values are not important.
        BatchBindStep batchBindStep = ctx.batch(
                ctx.insertInto(CONNECTOR_METER_VALUE,
                        CONNECTOR_METER_VALUE.CONNECTOR_PK,
                        CONNECTOR_METER_VALUE.TRANSACTION_PK,
                        CONNECTOR_METER_VALUE.VALUE_TIMESTAMP,
                        CONNECTOR_METER_VALUE.VALUE,
                        CONNECTOR_METER_VALUE.READING_CONTEXT,
                        CONNECTOR_METER_VALUE.FORMAT,
                        CONNECTOR_METER_VALUE.MEASURAND,
                        CONNECTOR_METER_VALUE.LOCATION,
                        CONNECTOR_METER_VALUE.UNIT)
                   .values(0, null, null, null, null, null, null, null, null)
        );

        // OCPP 1.5 allows multiple "values" elements
        for (MeterValue valuesElement : list) {
            DateTime timestamp = valuesElement.getTimestamp();

            // OCPP 1.5 allows multiple "value" elements under each "values" element.
            List<MeterValue.Value> valueList = valuesElement.getValue();
            for (MeterValue.Value valueElement : valueList) {

                ReadingContext context = valueElement.getContext();
                ValueFormat format = valueElement.getFormat();
                Measurand measurand = valueElement.getMeasurand();
                Location location = valueElement.getLocation();
                UnitOfMeasure unit = valueElement.getUnit();

                // OCPP 1.5 allows for each "value" element to have optional attributes
                String contextValue     = (context == null)   ? null : context.value();
                String formatValue      = (format == null)    ? null : format.value();
                String measurandValue   = (measurand == null) ? null : measurand.value();
                String locationValue    = (location == null)  ? null : location.value();
                String unitValue        = (unit == null)      ? null : unit.value();

                batchBindStep.bind(connectorPk, transactionId, timestamp, valueElement.getValue(),
                                   contextValue, formatValue, measurandValue, locationValue, unitValue);
            }
        }

        batchBindStep.execute();
    }
}
