import { describe, expect, it } from 'vitest';
import { composeMessage } from './compose';

const source = { accountId: 1, fromAddress: 'Alex <alex@example.com>', recipients: 'Me <me@example.com>, Alex <alex@example.com>, "River, Sam" <sam@example.com>, SAM@example.com',
  subject: 'Project update', textBody: 'The latest update.', internetMessageId: '<original@example.com>', referencesHeader: '<earlier@example.com>' };

describe('compose message context', () => {
  it('replies to the sender and keeps the source conversation headers', () => {
    expect(composeMessage('reply', source)).toMatchObject({ title: 'Reply', to: 'alex@example.com', cc: '', subject: 'Re: Project update',
      inReplyTo: source.internetMessageId, referencesHeader: '<earlier@example.com> <original@example.com>' });
  });
  it('reply all excludes the sending account and duplicates, including quoted display names', () => {
    expect(composeMessage('replyAll', source, 'ME@example.com').cc).toBe('sam@example.com');
  });
  it('forwards without inheriting reply headers or recipients', () => {
    expect(composeMessage('forward', source)).toMatchObject({ title: 'Forward', to: '', cc: '', subject: 'Fwd: Project update', inReplyTo: null, referencesHeader: null });
  });
  it('starts a clean message even while another message is selected', () => {
    expect(composeMessage('new', source)).toMatchObject({ title: 'New message', to: '', subject: '', body: '', inReplyTo: null, referencesHeader: null });
  });
  it('does not duplicate existing subject prefixes or change context when selection changes', () => {
    const changingSource = { ...source, subject: 'Re: Project update' };
    const draft = composeMessage('reply', changingSource);
    changingSource.internetMessageId = '<different@example.com>';
    expect(draft.subject).toBe('Re: Project update');
    expect(draft.inReplyTo).toBe('<original@example.com>');
  });
});
