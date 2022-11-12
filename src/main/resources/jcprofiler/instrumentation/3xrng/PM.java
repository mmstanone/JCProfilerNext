package example;
import javacard.framework.APDU;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;
import javacard.security.RandomData;

public class PM {
    private static void rng() {
        RandomData m_rngRandom = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
        byte[] buffer = new byte[16];
        short range = 16;
        m_rngRandom.generateData(buffer, (short) 0, range);
    }

    public static void check(short stopCondition) {
        for (short i = 0; i < 20; i++) {
            PM.rng();
        }
    }

    public static void set(APDU apdu) {}
}