import {describe,it,expect} from 'vitest';
import {readMailLocation,mailLocationUrl} from './mail-navigation';
describe('mail locations',()=>{
  it('round trips mailbox selection, filtering, search, page and message',()=>{
    const url='/mail/accounts/7?filter=unread&q=hello+world&page=3&folder=2&message=42';
    const route=readMailLocation(new URL(url,'http://localhost'));
    expect(route).toEqual({view:'inbox',accountId:7,folderId:2,filter:'unread',query:'hello world',page:2,messageId:42});
    expect(mailLocationUrl(route)).toBe(url);
  });
  it('accepts only filters supported by each view and discards malformed ids',()=>{
    expect(readMailLocation(new URL('http://localhost/mail/trash?filter=drafts')).filter).toBe('drafts');
    expect(readMailLocation(new URL('http://localhost/mail/contacts?filter=unread&page=-8&message=4')).filter).toBe('all');
    expect(readMailLocation(new URL('http://localhost/mail/sent?message=4')).messageId).toBeNull();
    expect(readMailLocation(new URL('http://localhost/mail/accounts/999999999999999999999')).accountId).toBeNull();
    expect(readMailLocation(new URL('http://localhost/mail?message=NaN&folder=-1&page=0')).page).toBe(0);
  });
  it('preserves the selected account when a trash view is bookmarked or reloaded',()=>{
    const url='/mail/trash?account=7&filter=drafts&page=2';
    const route=readMailLocation(new URL(url,'http://localhost'));
    expect(route).toMatchObject({view:'trash',accountId:7,filter:'drafts',page:1});
    expect(mailLocationUrl(route)).toBe(url);
  });
  it('preserves the selected account for drafts',()=>{
    const url='/mail/drafts?account=7';
    expect(readMailLocation(new URL(url,'http://localhost')).accountId).toBe(7);
    expect(mailLocationUrl(readMailLocation(new URL(url,'http://localhost')))).toBe(url);
  });
});
