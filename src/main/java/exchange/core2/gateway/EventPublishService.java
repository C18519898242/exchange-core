package exchange.core2.gateway;

import exchange.core2.core.IEventsHandler;
import exchange.core2.core.common.api.ApiAddUser;
import lombok.extern.slf4j.Slf4j;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.gateway.proto.AdminEvent;
import exchange.core2.gateway.proto.CommandResult;
import exchange.core2.gateway.proto.ResultCode;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

@Slf4j
public class EventPublishService implements IEventsHandler {

    private final ExcerptAppender appender;

    public EventPublishService() {
        // Initialize Chronicle Queue
        SingleChronicleQueue adminQueue = AdminQueueService.getInstance().getQueue();
        this.appender = adminQueue.createAppender();
    }

    @Override
    public void commandResult(IEventsHandler.ApiCommandResult commandResult) {
        // Only handle administrative command results
        if (isAdministrativeCommand(commandResult.getCommand())) {

            ApiAddUser addUserCmd = (ApiAddUser) commandResult.getCommand();

            // Convert internal event to Protobuf event
            CommandResult protoResult = CommandResult.newBuilder()
                    .setUid(addUserCmd.uid)
                    .setResultCode(toGrpcResultCode(commandResult.getResultCode()))
                    .setMessage(commandResult.getResultCode().toString())
                    .build();

            AdminEvent protoEvent = AdminEvent.newBuilder()
                    .setCommandResult(protoResult)
                    .build();

            // Write to the queue
            log.debug("Publishing event to queue: {}", protoEvent);
            appender.writeBytes(b -> b.write(protoEvent.toByteArray()));
        }
    }

    private boolean isAdministrativeCommand(ApiCommand command) {
        return command instanceof ApiAddUser;
    }

    private ResultCode toGrpcResultCode(CommandResultCode resultCode) {
        switch (resultCode) {
            case SUCCESS:
                return ResultCode.SUCCESS;
            case USER_MGMT_USER_ALREADY_EXISTS:
                return ResultCode.USER_ALREADY_EXISTS;
            default:
                return ResultCode.UNRECOGNIZED;
        }
    }

    @Override
    public void tradeEvent(TradeEvent tradeEvent) {
        // Not an admin event, ignore
    }

    @Override
    public void rejectEvent(RejectEvent rejectEvent) {
        // Not an admin event, ignore
    }

    @Override
    public void reduceEvent(ReduceEvent reduceEvent) {
        // Not an admin event, ignore
    }

    @Override
    public void orderBook(OrderBook orderBook) {
        // Not an admin event, ignore
    }
}
