import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wbrowser/blocker/filter_parser.dart';

void main() {
  test('parses domain, option and cosmetic rules', () {
    final r = FilterParser.parse([
      '! comment',
      '||ads.example.com^',
      '||tracker.net^\$third-party,script',
      '/banner/*/img',
      '##.adsbygoogle',
      'site.com##.promo',
      'site.com##+js(abort-on-property-read, x)',
      '||x.com^\$redirect=noop.js',
      '@@||ads.example.com^',
    ]);
    expect(r.network.length, 3);
    expect(r.network[1].trigger.loadType, [ContentBlockerTriggerLoadType.THIRD_PARTY]);
    expect(r.network[1].trigger.resourceType, [ContentBlockerTriggerResourceType.SCRIPT]);
    expect(r.globalCosmetic, ['.adsbygoogle']);
    expect(r.domainCosmetic['site.com'], ['.promo']);
    expect(r.exceptionDomains, {'ads.example.com'});
    expect(r.network.first.trigger.urlFilter, r'^[^:]+:(//)?([^/]+\.)?ads\.example\.com');
  });
}
