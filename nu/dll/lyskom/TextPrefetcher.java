package nu.dll.lyskom;

import java.io.IOException;
import java.util.List;

class TextPrefetcher implements Runnable {
    List<Integer> texts;
    Session session;

    public TextPrefetcher(Session session, List<Integer> texts) {
        this.session = session;
        this.texts = texts;
    }

    public void run() {
        Debug.println("TextPrefetcher: " + texts.size()
                + " texts to be fetched");
        while (!texts.isEmpty()) {
            int textNo;
            synchronized (texts) {
                textNo = texts.remove(0).intValue();
            }
            try {
                Text text = session.getText(textNo, true);
                int[] commented = text.getCommented();
                for (int i = 0; i < commented.length; i++) {
                    Debug.println("prefetching text-stat for " + commented[i]);
                    session.getTextStat(commented[i], true);
                }

                Debug.println("Fetched text number " + textNo);
            } catch (IOException ex1) {
                Debug.println("I/O error during pre-fetch: " + ex1.getMessage());
            }
        }
        Debug.println("Prefetch list emtpy");
    }
}
