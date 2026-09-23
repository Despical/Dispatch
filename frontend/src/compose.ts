export type ComposeMode = 'new' | 'reply' | 'replyAll' | 'forward';
type SourceMessage = { accountId: number; fromAddress: string; recipients: string; subject: string; textBody: string; internetMessageId: string | null; referencesHeader: string | null };

export function composeMessage(mode: ComposeMode, source: SourceMessage | null, ownAddress = '') {
  const result = { title: { new: 'New message', reply: 'Reply', replyAll: 'Reply all', forward: 'Forward' }[mode],
    to: '', cc: '', subject: '', body: '', inReplyTo: null as string | null, referencesHeader: null as string | null };
  if (!source || mode === 'new') return result;
  const email = (value: string) => value.match(/<([^>]+)>/)?.[1]?.trim() ?? value.trim();
  const reply = mode === 'reply' || mode === 'replyAll';
  const prefix = reply ? 'Re:' : 'Fwd:';
  result.subject = source.subject.toLowerCase().startsWith(prefix.toLowerCase()) ? source.subject : `${prefix} ${source.subject}`;
  if (reply) {
    result.to = email(source.fromAddress);
    result.inReplyTo = source.internetMessageId;
    result.referencesHeader = [source.referencesHeader, source.internetMessageId].filter(Boolean).join(' ') || null;
    if (mode === 'replyAll') {
      const seen = new Set([ownAddress.toLowerCase(), result.to.toLowerCase()]);
      result.cc = (source.recipients.match(/(?:"[^"]*"|[^,])+/g) ?? []).map(email).filter(address => {
        const key = address.toLowerCase();
        if (!key || seen.has(key)) return false;
        seen.add(key); return true;
      }).join(', ');
    }
  }
  result.body = `\n\n--- Original message ---\nFrom: ${source.fromAddress}\nTo: ${source.recipients}\nSubject: ${source.subject}\n\n${source.textBody ?? ''}`;
  return result;
}
