/* Where each project is, and who it serves: shared by /stories and every page that links into it.
   Regions come from each project's GPS point (OpenStreetMap reverse lookup, October 2026);
   projects added later fall back to the place names typed on the board. */
(function () {
  var NAMES = {"uasin-gishu": ["ke", "Uasin Gishu (Eldoret)"], "nandi": ["ke", "Nandi"], "kisumu": ["ke", "Kisumu area"], "naivasha": ["ke", "Naivasha area"], "mombasa": ["ke", "Mombasa"], "manyara": ["tz", "Manyara Region"], "arusha": ["tz", "Arusha Region"], "iringa": ["tz", "Iringa Region"], "tanga": ["tz", "Tanga Region"], "mara": ["tz", "Mara Region"], "mbeya": ["tz", "Mbeya Region"], "kampala": ["ug", "Kampala"], "kitgum": ["ug", "Kitgum (Northern Uganda)"]};
  var BY_ID = {"9782065871":"uasin-gishu","12846448452":"manyara","12109525694":"arusha","11847781320":"manyara","10853634206":"manyara","10853682904":"manyara","10853631994":"manyara","10853589699":"manyara","10853595419":"manyara","12481536502":"manyara","10853688890":"manyara","10838480532":"manyara","9903411129":"naivasha","9782081341":"nandi","18050463421":"manyara","11474727242":"manyara","11283541860":"manyara","10853502181":"manyara","11889078402":"manyara","12481452126":"manyara","18050948766":"manyara","9954138935":"manyara","18050820770":"manyara","12588089328":"manyara","12481577627":"manyara","11889173414":"manyara","10032585817":"tanga","12481382857":"manyara","12010681411":"manyara","11888941022":"manyara","12846267171":"manyara","12588140183":"manyara","12481605055":"manyara","12846260239":"manyara","9876875400":"uasin-gishu","12846391900":"manyara","10853690417":"manyara","12846116158":"manyara","11999998793":"manyara","9782123560":"uasin-gishu","12481321872":"manyara","12481269955":"manyara","9782119801":"uasin-gishu","10853507658":"manyara","12010669910":"manyara","11889402840":"manyara","12481670437":"manyara","12588050315":"manyara","10853655209":"manyara","12110006812":"arusha","9888388986":"uasin-gishu","9782106847":"uasin-gishu","10016723085":"kisumu","9782171935":"nandi","9782182766":"nandi","9854088431":"uasin-gishu","9641067786":"uasin-gishu","9641130148":"uasin-gishu","9641117855":"uasin-gishu","9641088376":"uasin-gishu","9641008540":"uasin-gishu","9641208129":"uasin-gishu","9641222619":"uasin-gishu","9641185691":"uasin-gishu","9782052026":"uasin-gishu","9898921216":"manyara","9898933179":"manyara","9614325378":"manyara","9625618800":"iringa","9550606331":"manyara","9782194685":"nandi","18067785818":"kampala","9782057755":"nandi","9645150585":"arusha","9550608154":"manyara","18050308580":"kitgum","10870383999":"arusha","10870458171":"arusha","10870603343":"arusha","9638111018":"naivasha","9568934943":"manyara","18050586759":"manyara","9568901597":"manyara","9569006332":"manyara","9854301083":"manyara","9854246559":"manyara","18050792240":"manyara","9419015628":"tanga","9568836868":"manyara","9898945725":"manyara","9568846047":"manyara","18051006570":"manyara","9418750662":"arusha","9854093332":"manyara","9854157225":"manyara","9550525897":"manyara","10084216693":"uasin-gishu","9782198302":"nandi","10084176605":"nandi","9568873269":"manyara","9954220897":"manyara","9782087184":"nandi","9590604142":"arusha","9590461489":"arusha","9590494156":"arusha","9590441544":"arusha","9590695503":"arusha","9590729065":"arusha","9590767570":"arusha","9590756846":"arusha","9590714693":"arusha","9590741290":"arusha","9619838126":"arusha","9619930709":"arusha","9620784446":"arusha","9645234345":"arusha","9644765024":"arusha","9621638692":"arusha","9600083364":"arusha","9643763252":"arusha","9640671401":"arusha","9643906331":"arusha","9621281106":"arusha","9550555271":"manyara","9490389538":"mara","9490461053":"arusha","9550530329":"manyara","9644017261":"arusha","9640730874":"arusha","9637591783":"mbeya","9636936486":"manyara","9625604485":"iringa","9639825187":"arusha","9621722824":"arusha","9418545886":"manyara","9625633129":"manyara","9595063226":"manyara","9594869918":"arusha","9594932537":"manyara","9595088130":"arusha","9640598947":"arusha","9594700415":"manyara","9594762830":"arusha","9640686055":"mombasa","9600073804":"arusha","9600039643":"arusha","9600012597":"arusha","9599910299":"arusha","9599983160":"arusha","9600031228":"arusha","9599947809":"arusha","9600021356":"arusha","9600050142":"arusha","9599971946":"arusha","9599991695":"arusha","9600000697":"arusha","9600062573":"arusha","9634364385":"manyara","10032500778":"tanga","12586164212":"manyara","12588150921":"manyara","9620829085":"arusha","9620887128":"arusha","9620919520":"arusha","9620995726":"arusha","9621032912":"arusha","12480701913":"nandi"};
  var KW = [
    ['uasin-gishu', /eldoret|kapseret|kesses|moiben|ziwa|soy\b|kaigat|simat|sosiani|turbo|chepkongi|kamng/i],
    ['nandi', /ndalat|kapsabet|kapsabaot|nandi|kaptebee|mosop/i],
    ['kisumu', /kisumu|seme|urudi/i],
    ['naivasha', /naivasha|longonot/i],
    ['mombasa', /mombasa|shanzu|kilifi/i],
    ['manyara', /manyara|babati|magara|kisese|gichameda|mbugwe|mayoka|maweni|migungani|ngarenaro|jangwani|mabrute/i],
    ['arusha', /arusha|sakina|oldonyosambu|lamanda|usa river|kikatiti|meru|arumeru|monduli/i],
    ['iringa', /iringa|mafinga/i],
    ['tanga', /tanga|lushoto|kifungilo|masange/i],
    ['mara', /mara\b|tarime|komaswa/i],
    ['mbeya', /mbeya|mbarali/i],
    ['kampala', /kampala/i],
    ['kitgum', /kitgum/i]
  ];
  function region(p) {
    if (BY_ID[p.id]) return BY_ID[p.id];
    var t = (p.area || '') + ' ' + (p.title || '');
    for (var i = 0; i < KW.length; i++) if (NAMES[KW[i][0]][0] === p.ck && KW[i][1].test(t)) return KW[i][0];
    return '';
  }
  // who a project serves, from its name
  var SERVE = [
    ['clinic', 'Health clinics', /clinic|dispensar|zahanati|hospital|medical|health/i],
    ['school', 'Schools', /school|shule|primary|secondary|academy|ecde|preschool|kinder|classroom|college/i],
    ['church', 'Churches', /church|kanisa|parish|ack\b|pag\b|aic\b|kkkt|lutheran|calvary|sabato|mosque|msikiti/i],
    ['children', "Children's homes", /orphan|children's home|childrens home|child of god|rescue/i]
  ];
  function serves(p) {
    var t = p.title || '';
    for (var i = 0; i < SERVE.length; i++) if (SERVE[i][2].test(t)) return SERVE[i][0];
    return p.cat === 'livelihoods' ? 'families' : 'village';
  }
  window.rafikiPlaces = { names: NAMES, region: region, serves: serves,
    serveNames: { clinic: 'Health clinics', school: 'Schools', church: 'Churches', children: "Children's homes", village: 'Villages and communities', families: 'Families' } };
})();
