package confidential.statemanagement.resharing;

import confidential.polynomial.PolynomialCreationContext;
import confidential.server.ServerConfidentialityScheme;
import confidential.statemanagement.ReconstructionCompleted;
import vss.Utils;
import vss.commitment.Commitment;
import vss.secretsharing.VerifiableShare;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

public class ConstantBlindedStateHandler extends BlindedStateHandler {
    private final Map<Integer, ObjectInput> commitmentsStreams;
    private Map<BigInteger, Commitment> allBlindingCommitments;
    private Commitment blindingCommitment;

    public ConstantBlindedStateHandler(int processId, PolynomialCreationContext context,
                                       VerifiableShare refreshPoint, ServerConfidentialityScheme confidentialityScheme,
                                       int stateSenderReplica, int serverPort, ReconstructionCompleted reconstructionCompleted) {
        super(processId, context, refreshPoint, confidentialityScheme, stateSenderReplica, serverPort, reconstructionCompleted);
        this.commitmentsStreams = new HashMap<>(oldQuorum);
    }

    @Override
    protected void handleNewCommitments(int from, byte[] serializedCommitments, byte[] commitmentsHash) {
        try {
            commitmentsStreams.put(from, new ObjectInputStream(new ByteArrayInputStream(serializedCommitments)));
        } catch (IOException e) {
            logger.error("Failed open stream to read commitments from {}", from, e);
        }
    }

    @Override
    protected boolean prepareCommitments() {
        return commitmentsStreams.size() >= oldQuorum;
    }

    @Override
    protected Commitment readBlindingCommitment() throws IOException, ClassNotFoundException {
        allBlindingCommitments = nextCommitment();
        blindingCommitment = commitmentScheme.combineCommitments(allBlindingCommitments);
        return blindingCommitment;
    }

    @Override
    protected Map<BigInteger, Commitment> readNextCommitment() throws IOException, ClassNotFoundException {
        return nextCommitment();
    }

    @Override
    protected Commitment removeServersCommitment(int server) {
        commitmentsStreams.remove(server);
        allBlindingCommitments.remove(confidentialityScheme.getShareholder(server));
        blindingCommitment = commitmentScheme.combineCommitments(allBlindingCommitments);
        return blindingCommitment;
    }

    private Map<BigInteger, Commitment> nextCommitment() throws IOException, ClassNotFoundException {
        Map<BigInteger, Commitment> commitments =
                new HashMap<>(commitmentsStreams.size());

        for (Map.Entry<Integer, ObjectInput> entry : commitmentsStreams.entrySet()) {
            Commitment commitment = Utils.readCommitment(entry.getValue());

            BigInteger shareholder =
                    confidentialityScheme.getShareholder(entry.getKey());
            commitments.put(shareholder, commitment);
        }

        return commitments;
    }
}