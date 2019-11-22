package confidential;

import bftsmart.reconfiguration.views.View;
import vss.Constants;
import vss.commitment.CommitmentScheme;
import vss.facade.SecretSharingException;
import vss.facade.VSSFacade;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static confidential.Configuration.*;

/**
 * @author Robin
 */
public abstract class CobraConfidentialityScheme {
    protected final VSSFacade vss;
    private final Map<Integer, BigInteger> serverToShareholder;
    private final Map<BigInteger, Integer> shareholderToServer;

    public CobraConfidentialityScheme(View view) throws SecretSharingException {
        int[] processes = view.getProcesses();
        serverToShareholder = new HashMap<>(processes.length);
        shareholderToServer = new HashMap<>(processes.length);
        BigInteger[] shareholders = new BigInteger[processes.length];
        for (int i = 0; i < processes.length; i++) {
            int process = processes[i];
            BigInteger shareholder = BigInteger.valueOf(process + 1);
            serverToShareholder.put(process, shareholder);
            shareholderToServer.put(shareholder, process);
            shareholders[i] = shareholder;
        }

        int threshold = view.getF();

        Properties properties = new Properties();
        properties.put(Constants.TAG_THRESHOLD, String.valueOf(threshold));
        properties.put(Constants.TAG_PRIME_FIELD, str_p);
        properties.put(Constants.TAG_SUB_FIELD, str_field);
        properties.put(Constants.TAG_GENERATOR, str_generator);
        properties.put(Constants.TAG_DATA_ENCRYPTION_ALGORITHM, dataEncryptionAlgorithm);
        properties.put(Constants.TAG_SHARE_ENCRYPTION_ALGORITHM, shareEncryptionAlgorithm);
        properties.put(Constants.TAG_COMMITMENT_SCHEME, Constants.VALUE_KATE_SCHEME);
        //properties.put(Constants.TAG_COMMITMENT_SCHEME, Constants.VALUE_FELDMAN_SCHEME);

        vss = new VSSFacade(properties, shareholders);
    }

    public CommitmentScheme getCommitmentScheme() {
        return vss.getCommitmentScheme();
    }

    public BigInteger getShareholder(int process) {
        return serverToShareholder.get(process);
    }

    public int getProcess(BigInteger shareholder) {
        return shareholderToServer.get(shareholder);
    }

    public void updateParameters(View view) {
        throw new UnsupportedOperationException("Not implemented");
    }
}