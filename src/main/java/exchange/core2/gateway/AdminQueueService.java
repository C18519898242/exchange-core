package exchange.core2.gateway;

import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

public final class AdminQueueService {

    private static final AdminQueueService INSTANCE = new AdminQueueService();

    private final SingleChronicleQueue queue;

    private AdminQueueService() {
        String queuePath = "path/to/admin-queue";
        this.queue = SingleChronicleQueueBuilder.binary(queuePath).build();
    }

    public static AdminQueueService getInstance() {
        return INSTANCE;
    }

    public SingleChronicleQueue getQueue() {
        return queue;
    }
}
