package confidential.statemanagement;

import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.reconfiguration.ServerViewController;
import bftsmart.tom.MessageContext;
import bftsmart.tom.server.defaultservices.CommandsInfo;
import bftsmart.tom.server.defaultservices.DefaultApplicationState;
import bftsmart.tom.util.TOMUtil;
import confidential.ConfidentialData;
import confidential.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vss.secretsharing.Share;
import vss.secretsharing.VerifiableShare;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.LinkedList;

/**
 * @author Robin
 */
public class RecoveryStateSender extends Thread {
    private Logger logger = LoggerFactory.getLogger("confidential");
    private static final String SECRET = "MySeCreT_2hMOygBwY";
    private SSLServerSocket serverSocket;
    private int myProcessId;
    private String recoveringServerIp;
    private DefaultApplicationState state;
    private VerifiableShare recoveryPoint;
    private BigInteger field;
    private boolean iAmStateSender;

    RecoveryStateSender(int serverPort, String recoveringServerIp,
                        DefaultApplicationState applicationState, VerifiableShare recoveryPoint,
                        BigInteger field, ServerViewController svController, boolean iAmStateSender) throws Exception {
        super("State Sender Thread");
        logger.debug("I am listening in port {} for state request", serverPort);
        this.serverSocket = createSSLServerSocket(serverPort, svController);
        this.recoveringServerIp = recoveringServerIp;
        this.state = applicationState;
        this.recoveryPoint = recoveryPoint;
        this.myProcessId = svController.getStaticConf().getProcessId();
        this.field = field;
        this.iAmStateSender = iAmStateSender;
    }

    private SSLServerSocket createSSLServerSocket(int serverPort, ServerViewController svController)
            throws IOException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException,
            CertificateException, UnrecoverableKeyException {
        KeyStore ks;
        try (FileInputStream fis = new FileInputStream("config/keysSSL_TLS/" +
                svController.getStaticConf().getSSLTLSKeyStore())) {
            ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(fis, SECRET.toCharArray());
        }

        String algorithm = Security.getProperty("ssl.KeyManagerFactory.algorithm");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);
        kmf.init(ks, SECRET.toCharArray());

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(algorithm);
        trustManagerFactory.init(ks);

        SSLContext context = SSLContext.getInstance(svController.getStaticConf().getSSLTLSProtocolVersion());
        context.init(kmf.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());

        SSLServerSocketFactory serverSocketFactory = context.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) serverSocketFactory.createServerSocket(serverPort);
        serverSocket.setEnabledCipherSuites(svController.getStaticConf().getEnabledCiphers());
        serverSocket.setEnableSessionCreation(true);
        serverSocket.setReuseAddress(true);
        serverSocket.setNeedClientAuth(true);
        serverSocket.setWantClientAuth(true);

        return serverSocket;
    }

    @Override
    public void run() {
        logger.debug("Generating recovery state");
        RecoveryApplicationState recoveryState = createRecoverState();
        if (recoveryState == null) {
            logger.error("Failed to generate recovery application state. Exiting state sender server thread.");
            return;
        }
        if (iAmStateSender) {
            logger.debug("Recovery state generated, has {} bytes and {} shares", recoveryState.getCommonState().length,
                    recoveryState.getShares().size());
        } else {
            logger.debug("Recovery state generated, has {} bytes of hash and {} shares",
                    recoveryState.getCommonStateHash().length, recoveryState.getShares().size());
        }
        while (true) {
            try {
                //SSLSocket client = (SSLSocket) serverSocket.accept();
                Socket client = serverSocket.accept();
                String clientIp = client.getInetAddress().getHostAddress();
                if (!clientIp.equals(recoveringServerIp)) {
                    logger.info("Received unexpected server connection asking state from {}. I am ignoring it!", clientIp);
                    client.close();
                    continue;
                }
                ObjectOutput out = new ObjectOutputStream(client.getOutputStream());
                logger.debug("Transmitting recovery state to {}", clientIp);

                recoveryState.getTransferPolynomialCommitments().writeExternal(out);
                out.writeInt(recoveryState.getLastCheckpointCID());
                out.writeInt(recoveryState.getLastCID());
                out.writeInt(recoveryState.getPid());
                out.writeInt(recoveryState.getShares().size());
                for (Share share : recoveryState.getShares()) {
                    share.writeExternal(out);
                }
                byte[] commonState = recoveryState.getCommonState();
                out.writeBoolean(commonState != null);
                if (commonState != null) {
                    out.writeInt(commonState.length);
                    int i = 0;
                    while (i < commonState.length) {
                        int len = Math.min(1024, commonState.length - i);
                        out.write(commonState, i, len);
                        i += len;
                    }
                } else {
                    out.writeInt(recoveryState.getCommonStateHash().length);
                    out.write(recoveryState.getCommonStateHash());
                }
                out.flush();
                logger.debug("Recovery state sent");
                serverSocket.close();
                break;
            } catch (IOException e) {
                logger.error("Failed to accept recovering server request", e);
            }
        }
        logger.debug("Exiting state sender server thread");
    }

    private RecoveryApplicationState createRecoverState() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bos)) {
            CommandsInfo[] log = state.getMessageBatches();
            out.writeInt(log == null ? -1 : log.length);
            LinkedList<Share> shares = new LinkedList<>();
            byte[] b;
            if (log != null) {
                for (CommandsInfo commandsInfo : log) {
                    byte[][] commands = commandsInfo.commands;
                    MessageContext[] msgCtx = commandsInfo.msgCtx;
                    serializeMessageContext(out, msgCtx);
                    out.writeInt(commands.length);
                    for (byte[] command : commands) {
                        Request request = Request.deserialize(command);
                        if (request == null || request.getShares() == null) {
                            out.writeInt(-1);
                            out.writeInt(command.length);
                            out.write(command);
                        } else {
                            out.writeInt(request.getShares().length);
                            for (ConfidentialData share : request.getShares()) {
                                b = share.getShare().getSharedData();
                                out.writeInt(b == null ? -1 : b.length);
                                if (b != null)
                                    out.write(b);
                                share.getShare().getCommitments().writeExternal(out);

                                Share transferShare = share.getShare().getShare();
                                transferShare.setShare(transferShare.getShare().add(recoveryPoint.getShare().getShare()).mod(field));
                                shares.add(transferShare);

                                out.writeInt(share.getPublicShares() == null ? -1 : share.getPublicShares().size());
                                if (share.getPublicShares() != null) {//writing public data
                                    for (VerifiableShare publicShare : share.getPublicShares()) {
                                        publicShare.writeExternal(out);
                                    }
                                }
                            }
                            request.setShares(null);
                            b = request.serialize();
                            if (b == null) {
                                logger.debug("Failed to serialize recovery Request");
                                return null;
                            }
                            out.writeInt(b.length);
                            out.write(b);
                        }
                    }
                }
            }

            if (state.hasState()) {
                ConfidentialSnapshot snapshot = ConfidentialSnapshot.deserialize(state.getSerializedState());
                if (snapshot != null) {
                    out.writeBoolean(true);
                    out.writeInt(snapshot.getPlainData() == null ? -1 : snapshot.getPlainData().length);
                    if (snapshot.getPlainData() != null)
                        out.write(snapshot.getPlainData());
                    out.writeInt(snapshot.getShares() == null ? -1 : snapshot.getShares().length);
                    if (snapshot.getShares() != null) {
                        for (ConfidentialData share : snapshot.getShares()) {
                            b = share.getShare().getSharedData();
                            out.writeInt(b == null ? -1 : b.length);
                            if (b != null)
                                out.write(b);
                            share.getShare().getCommitments().writeExternal(out);
                            Share transferShare = share.getShare().getShare();
                            transferShare.setShare(transferShare.getShare().add(recoveryPoint.getShare().getShare()).mod(field));
                            shares.add(transferShare);

                            out.writeInt(share.getPublicShares() == null ? -1 : share.getPublicShares().size());
                            if (share.getPublicShares() != null) {//writing public data
                                for (VerifiableShare publicShare : share.getPublicShares()) {
                                    publicShare.writeExternal(out);
                                }
                            }
                        }
                    }
                } else
                    out.writeBoolean(false);
            } else
                out.writeBoolean(false);

            out.flush();
            bos.flush();

            byte[] commonState = bos.toByteArray();
            //logger.debug("Common State: {}", commonState);

            if (iAmStateSender) {
                return new RecoveryApplicationState(
                        commonState,
                        shares,
                        state.getLastCheckpointCID(),
                        state.getLastCID(),
                        myProcessId,
                        recoveryPoint.getCommitments()
                );
            } else {
                return new RecoveryApplicationState(
                        null,
                        TOMUtil.computeHash(commonState),
                        shares,
                        state.getLastCheckpointCID(),
                        state.getLastCID(),
                        myProcessId,
                        recoveryPoint.getCommitments()
                );
            }

        } catch (IOException e) {
            logger.error("Failed to create Recovery State", e);
        }
        return null;
    }

    private byte[] computeHash(byte[] data) {
        try {
            MessageDigest digest = TOMUtil.getHashEngine();
            int i = 0;
            while (i < data.length) {
                int len = Math.min(1024, data.length - i);
                digest.update(data, i, len);
                i += len;
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            logger.error("Failed to hash data", e);
        }
        return null;
    }

    private void serializeMessageContext(ObjectOutputStream out, MessageContext[] msgCtx) throws IOException {
        out.writeInt(msgCtx == null ? -1 : msgCtx.length);
        if (msgCtx == null)
            return;
        for (MessageContext ctx : msgCtx) {
            out.writeInt(ctx.getSender());
            out.writeInt(ctx.getViewID());
            out.writeInt(ctx.getType().ordinal());
            out.writeInt(ctx.getSession());
            out.writeInt(ctx.getSequence());
            out.writeInt(ctx.getOperationId());
            out.writeInt(ctx.getReplyServer());
            out.writeInt(ctx.getSignature() == null ? -1 : ctx.getSignature().length);
            if (ctx.getSignature() != null)
                out.write(ctx.getSignature());

            out.writeLong(ctx.getTimestamp());
            out.writeInt(ctx.getRegency());
            out.writeInt(ctx.getLeader());
            out.writeInt(ctx.getConsensusId());
            out.writeInt(ctx.getNumOfNonces());
            out.writeLong(ctx.getSeed());
            out.writeInt(ctx.getProof() == null ? -1 : ctx.getProof().size());
            if (ctx.getProof() != null) {
                for (ConsensusMessage proof : ctx.getProof()) {
                    //out.writeInt(proof.getSender());
                    out.writeInt(proof.getNumber());
                    out.writeInt(proof.getEpoch());
                    out.writeInt(proof.getType());

                    out.writeInt(proof.getValue() == null ? -1 : proof.getValue().length);
                    if (proof.getValue() != null)
                        out.write(proof.getValue());
                    /*logger.debug("{}", proof.getProof());*/
                }
            }
            ctx.getFirstInBatch().wExternal(out);
            out.writeBoolean(ctx.isLastInBatch());
            out.writeBoolean(ctx.isNoOp());
            //out.writeBoolean(ctx.readOnly);

            out.writeInt(ctx.getNonces() == null ? -1 : ctx.getNonces().length);
            if (ctx.getNonces() != null)
                out.write(ctx.getNonces());
        }

    }
}